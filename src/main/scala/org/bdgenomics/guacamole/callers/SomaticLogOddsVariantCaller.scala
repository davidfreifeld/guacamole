package org.bdgenomics.guacamole.callers

import org.bdgenomics.guacamole._
import org.apache.spark.Logging
import org.bdgenomics.guacamole.Common.Arguments.{ TumorNormalReads, Output }
import org.kohsuke.args4j.{ Option => Opt }
import org.bdgenomics.adam.cli.Args4j
import org.bdgenomics.adam.avro.{ ADAMContig, ADAMVariant, ADAMGenotype }
import org.apache.spark.rdd.RDD
import org.bdgenomics.guacamole.concordance.GenotypesEvaluator
import org.bdgenomics.guacamole.concordance.GenotypesEvaluator.GenotypeConcordance
import org.bdgenomics.guacamole.pileup.Pileup
import scala.collection.JavaConversions
import org.bdgenomics.adam.util.PhredUtils
import org.bdgenomics.guacamole.filters.GenotypeFilter.GenotypeFilterArguments
import org.bdgenomics.guacamole.filters.PileupFilter.PileupFilterArguments
import org.bdgenomics.guacamole.filters.{ FishersExactTest, PileupFilter, GenotypeFilter }
import org.apache.commons.math3.util.ArithmeticUtils

/**
 * Simple subtraction based somatic variant caller
 *
 * This takes two variant callers, calls variants on tumor and normal independently
 * and outputs the variants in the tumor sample BUT NOT the normal sample
 *
 * This assumes that both read sets only contain a single sample, otherwise we should compare
 * on a sample identifier when joining the genotypes
 *
 */
object SomaticLogOddsVariantCaller extends Command with Serializable with Logging {
  override val name = "logodds-somatic"
  override val description = "call somatic variants using a two independent caller on tumor and normal"

  private class Arguments extends DistributedUtil.Arguments with Output with GenotypeConcordance with GenotypeFilterArguments with PileupFilterArguments with TumorNormalReads {
    @Opt(name = "-log-odds", metaVar = "X", usage = "Make a call if the probability of variant is greater than this value (Phred-scaled)")
    var logOdds: Int = 35

  }

  override def run(rawArgs: Array[String]): Unit = {

    val args = Args4j[Arguments](rawArgs)
    val sc = Common.createSparkContext(args, appName = Some(name))

    val filters = Read.InputFilters(mapped = true, nonDuplicate = true, hasMdTag = true, passedVendorQualityChecks = true)
    val (tumorReads, normalReads) = Common.loadTumorNormalReadsFromArguments(args, sc, filters)

    assert(tumorReads.sequenceDictionary == normalReads.sequenceDictionary,
      "Tumor and normal samples have different sequence dictionaries. Tumor dictionary: %s.\nNormal dictionary: %s."
        .format(tumorReads.sequenceDictionary, normalReads.sequenceDictionary))

    val minReadDepth = args.minReadDepth

    val lowStrandBiasLimit = args.lowStrandBiasLimit
    val highStrandBiasLimit = args.highStrandBiasLimit

    val maxNormalAlternateReadDepth = args.maxNormalAlternateReadDepth

    val oddsThreshold = args.logOdds

    val maxMappingComplexity = args.maxMappingComplexity
    val minAlignmentForComplexity = args.minAlignmentForComplexity
    val filterDeletionOverlap = args.filterDeletionOverlap

    val filterAmbiguousMapped = args.filterAmbiguousMapped
    val filterMultiAllelic = args.filterMultiAllelic
    val minAlignmentQuality = args.minAlignmentQuality
    val minLikelihood = args.minLikelihood
    val maxPercentAbnormalInsertSize = args.maxPercentAbnormalInsertSize

    val loci = Common.loci(args, normalReads)
    val lociPartitions = DistributedUtil.partitionLociAccordingToArgs(args, loci, tumorReads.mappedReads, normalReads.mappedReads)

    val genotypes: RDD[ADAMGenotype] = DistributedUtil.pileupFlatMapTwoRDDs[ADAMGenotype](
      tumorReads.mappedReads,
      normalReads.mappedReads,
      lociPartitions,
      true, // skip empty pileups
      (pileupTumor, pileupNormal) => callSomaticVariantsAtLocus(
        pileupTumor,
        pileupNormal,
        minLikelihood,
        oddsThreshold,
        minAlignmentQuality,
        lowStrandBiasLimit,
        highStrandBiasLimit,
        maxNormalAlternateReadDepth,
        maxMappingComplexity,
        minAlignmentForComplexity,
        filterAmbiguousMapped,
        filterMultiAllelic,
        filterDeletionOverlap,
        minReadDepth,
        maxPercentAbnormalInsertSize).iterator)

    genotypes.persist()
    val filteredGenotypes = GenotypeFilter(genotypes, args)
    Common.progress("Computed %,d genotypes".format(filteredGenotypes.count))

    Common.writeVariantsFromArguments(args, filteredGenotypes)
    if (args.truthGenotypesFile != "")
      GenotypesEvaluator.printGenotypeConcordance(args, filteredGenotypes, sc)

    DelayedMessages.default.print()
  }

  /**
   *
   * Computes the genotype and probability at a given locus
   *
   * @param tumorPileup
   * @param normalPileup
   * @param logOddsThreshold
   * @param minAlignmentQuality
   * @param maxMappingComplexity
   * @param filterAmbiguousMapped
   * @param filterMultiAllelic
   * @return Sequence of possible called genotypes for all samples
   */
  def callSomaticVariantsAtLocus(tumorPileup: Pileup,
                                 normalPileup: Pileup,
                                 minLikelihood: Int,
                                 logOddsThreshold: Int,
                                 minAlignmentQuality: Int,
                                 lowStrandBiasLimit: Int,
                                 highStrandBiasLimit: Int,
                                 maxNormalAlternateReadDepth: Int,
                                 maxMappingComplexity: Int,
                                 minAlignmentForComplexity: Int,
                                 filterAmbiguousMapped: Boolean,
                                 filterMultiAllelic: Boolean,
                                 filterDeletionOverlap: Boolean = false,
                                 minReadDepth: Int,
                                 maxPercentAbnormalInsertSize: Int): Seq[ADAMGenotype] = {

    val filteredNormalPileup = PileupFilter(normalPileup,
      filterAmbiguousMapped,
      filterMultiAllelic,
      maxMappingComplexity,
      minAlignmentForComplexity,
      minAlignmentQuality,
      maxPercentAbnormalInsertSize,
      filterDeletionOverlap)

    val filteredTumorPileup = PileupFilter(tumorPileup,
      filterAmbiguousMapped,
      filterMultiAllelic,
      maxMappingComplexity,
      minAlignmentForComplexity,
      minAlignmentQuality,
      maxPercentAbnormalInsertSize,
      filterDeletionOverlap)

    // For now, we skip loci that have no reads mapped. We may instead want to emit NoCall in this case.
    if (filteredTumorPileup.elements.isEmpty || filteredNormalPileup.elements.isEmpty || filteredNormalPileup.depth < minReadDepth)
      return Seq.empty

    val referenceBase = Bases.baseToString(normalPileup.referenceBase)
    val tumorSampleName = tumorPileup.elements(0).read.sampleName

    val tumorLikelihoods =
      BayesianQualityVariantCaller.computeLikelihoods(filteredTumorPileup,
        includeAlignmentLikelihood = true,
        normalize = true).toMap

    def normalPrior(gt: Genotype, hetVariantPrior: Double = 1e-3): Double = {
      val numberVariants = gt.numberOfVariants(referenceBase)
      if (numberVariants > 0) math.pow(hetVariantPrior / gt.uniqueAllelesCount, numberVariants) else 1
    }

    val mapTumorLikelihoods = BayesianQualityVariantCaller.normalize(
      tumorLikelihoods.map(genotypeLikelihood =>
        (genotypeLikelihood._1, genotypeLikelihood._2 * normalPrior(genotypeLikelihood._1))))

    val tumorMostLikelyGenotype = mapTumorLikelihoods.maxBy(_._2)

    if (!tumorMostLikelyGenotype._1.isVariant(referenceBase)) return Seq.empty

    val normalLikelihoods =
      BayesianQualityVariantCaller.computeLikelihoods(filteredNormalPileup,
        includeAlignmentLikelihood = true,
        normalize = true).toMap

    val mapNormalLikelihoods = BayesianQualityVariantCaller.normalize(
      normalLikelihoods.map(genotypeLikelihood =>
        (genotypeLikelihood._1, genotypeLikelihood._2 * normalPrior(genotypeLikelihood._1))))

    val (normalVariantGenotypes, normalReferenceGenotype) = mapNormalLikelihoods.partition(_._1.isVariant(referenceBase))

    //    val mostLikelyGenotype = computeSomaticLikelihood(referenceBase, filteredTumorPileup, filteredNormalPileup)
    //    val tumorMostLikelyGenotype = mostLikelyGenotype._1._1

    val somaticLogOdds = math.log(tumorMostLikelyGenotype._2) - math.log(normalVariantGenotypes.map(_._2).sum)
    val somaticVariantProbability = PhredUtils.successProbabilityToPhred(scaleratio(somaticLogOdds) - 1e-10)

    if (!tumorMostLikelyGenotype._1.isVariant(referenceBase)) return Seq.empty
    val alternateBase = tumorMostLikelyGenotype._1.getNonReferenceAlleles(referenceBase)(0)

    val (alternateReadDepth, alternateForwardReadDepth) = computeDepthAndForwardDepth(alternateBase, filteredTumorPileup)
    val (referenceReadDepth, referenceForwardReadDepth) = computeDepthAndForwardDepth(referenceBase, filteredTumorPileup)
    val (referenceNormalReadDepth, referenceNormalForwardReadDepth) = computeDepthAndForwardDepth(referenceBase, filteredNormalPileup)
    val (alternateNormalReadDepth, alternateNormalForwardReadDepth) = computeDepthAndForwardDepth(alternateBase, filteredNormalPileup)

    val phredScaledGenotypeLikelihood = PhredUtils.successProbabilityToPhred(tumorMostLikelyGenotype._2 - 1e-10)
    if (!tumorMostLikelyGenotype._1.isVariant(referenceBase) ||
      somaticVariantProbability < logOddsThreshold ||
      alternateNormalReadDepth > maxNormalAlternateReadDepth ||
      (phredScaledGenotypeLikelihood < minLikelihood && !somaticLogOdds.isInfinite)) return Seq.empty

    //    if (math.abs(strandBiasZScore) > 1.5) return Seq.empty
//    if (isBiasedToSingleStrand(alternateReadDepth,
//      alternateForwardReadDepth,
//      referenceReadDepth,
//      referenceForwardReadDepth,
//      lowStrandBiasLimit,
//      highStrandBiasLimit)) return Seq.empty

    def buildVariants(genotype: Genotype,
                      probability: Double,
                      readDepth: Int,
                      alternateReadDepth: Int,
                      alternateForwardDepth: Int,
                      delta: Double = 1e-10): Seq[ADAMGenotype] = {
      val genotypeAlleles = JavaConversions.seqAsJavaList(genotype.getGenotypeAlleles(referenceBase))
      genotype.getNonReferenceAlleles(referenceBase).map(
        variantAllele => {
          val variant = ADAMVariant.newBuilder
            .setPosition(normalPileup.locus)
            .setReferenceAllele(referenceBase)
            .setVariantAllele(variantAllele)
            .setContig(ADAMContig.newBuilder.setContigName(normalPileup.referenceName).build)
            .build
          ADAMGenotype.newBuilder
            .setAlleles(genotypeAlleles)
            .setGenotypeQuality(PhredUtils.successProbabilityToPhred(probability - delta))
            .setReadDepth(readDepth)
            .setExpectedAlleleDosage(alternateReadDepth.toFloat / readDepth)
            .setSampleId(tumorSampleName.toCharArray)
            .setAlternateReadDepth(alternateReadDepth)
            .setVariant(variant)
            .build
        })
    }

    buildVariants(tumorMostLikelyGenotype._1, tumorMostLikelyGenotype._2, filteredTumorPileup.depth, alternateReadDepth, alternateForwardReadDepth)
  }

  //  def computeSomaticLikelihood(referenceBase: String, tumorPileup: Pileup, normalPileup: Pileup, hetVariantPrior: Double = 1e-3, tumorNormalLinkPrior: Double = 1e-6): ((Genotype, Genotype), Double) = {
  //
  //    def normalPrior(gt: Genotype, hetVariantPrior: Double): Double = {
  //      val numberVariants = gt.numberOfVariants(referenceBase)
  //      if (numberVariants > 0) math.pow(hetVariantPrior / gt.uniqueAllelesCount, numberVariants) else 1
  //    }
  //
  //    def tumorPrior(tgt: Genotype, ngt: Genotype, tumorNormalLinkPrior: Double): Double = {
  //
  //      val sharedAlleles = tgt.alleles.toSet.intersect(ngt.alleles.toSet).size
  //      if (!tgt.equals(ngt)) tumorNormalLinkPrior else 1
  //      //if (sharedAlleles != tgt.ploidy) math.pow(tumorNormalLinkPrior, sharedAlleles) else 1
  //    }
  //
  //    val tumorLikelihoods = BayesianQualityVariantCaller.computeLikelihoods(tumorPileup, includeAlignmentLikelihood = false, normalize = true).toMap
  //    val normalLikelihoods = BayesianQualityVariantCaller.computeLikelihoods(normalPileup, includeAlignmentLikelihood = false, normalize = true).toMap
  //    val jointLikelihoods =
  //      tumorLikelihoods.flatMap(tumorGT => {
  //        normalLikelihoods.map(normalGT => {
  //          ((tumorGT._1, normalGT._1), tumorGT._2 * normalGT._2 * normalPrior(normalGT._1, hetVariantPrior) * normalPrior(tumorGT._1, hetVariantPrior) * tumorPrior(tumorGT._1, normalGT._1, tumorNormalLinkPrior))
  //        })
  //      })
  //    val totalLikelihood = jointLikelihoods.map(_._2).sum
  //    val normalizedJointLikelihoods = jointLikelihoods.map(jointLikelihood => (jointLikelihood._1, jointLikelihood._2 / totalLikelihood))
  //    normalizedJointLikelihoods.maxBy(_._2)
  //  }

  //  //TODO to apply strand bias filter here for now
  def strandBiasFilter(alternateReadDepth: Int,
                       alternateForwardReadDepth: Int,
                       referenceReadDepth: Int,
                       referenceForwardReadDepth: Int): Int = {

    //val p_ref_alt_equally_forward = fishersExactTest(totalForward, totalBackward, alternateForwardReadDepth, alternateReadDepth - alternateForwardReadDepth)
    val strandEquallyAlt = FishersExactTest(alternateReadDepth, referenceReadDepth, alternateForwardReadDepth, referenceForwardReadDepth)
    math.round(100 * (1 - strandEquallyAlt)).toInt

  }

  def isBiasedToSingleStrand(alternateReadDepth: Int,
                             alternateForwardReadDepth: Int,
                             referenceReadDepth: Int,
                             referenceForwardReadDepth: Int,
                             lowStrandBiasLimit: Int,
                             highStrandBiasLimit: Int): Boolean = {

    val altForwardRatio = math.round(100 * alternateForwardReadDepth / alternateReadDepth.toFloat).toInt
    val refForwardRatio = referenceForwardReadDepth / referenceReadDepth.toFloat
    if (altForwardRatio >= highStrandBiasLimit ||
      altForwardRatio <= lowStrandBiasLimit)
      true
    else
      false

  }

  def testStatistic(p1: Double, p2: Double, n1: Int, n2: Int): Double = {

    val p = ((p1 * n1) + (p2 * n2)) / (n1 + n2)
    val standardError = math.sqrt(p * (1 - p) * ((1.0 / n1) + (1.0 / n2)))

    (p1 - p2) / standardError
  }

  def computeDepthAndForwardDepth(base: String, filteredTumorPileup: Pileup): (Int, Int) = {
    val baseElements = filteredTumorPileup.elements.filter(el => Bases.basesToString(el.sequencedBases) == base)

    val readDepth = baseElements.length
    val baseForwardReadDepth = baseElements.filter(_.read.isPositiveStrand).length
    return (readDepth, baseForwardReadDepth)
  }

  def logistic(x: Double): Double = {
    1.0 / (1 + math.exp(-x))
  }

  def scaleratio(x: Double): Double = {
    1 - (1.0 / math.abs(x))
  }

  def gompertz(x: Double, c: Double = -0.5, a: Double = 1, b: Double = -1): Double = {
    math.exp(-math.exp(c * x))
  }

}

