/*
 * The MIT License
 *
 * Copyright (c) 2020 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.fingerprint;

import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.tribble.FeatureReader;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFReader;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.DiagnosticsAndQCProgramGroup;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Program to create a fingerprint for the <b>contaminating</b> sample when the level of contamination is both known and
 * uniform in the genome.
 *
 * @author Yossi Farjoun
 */
@CommandLineProgramProperties(
        summary = "Computes/Extracts the fingerprint genotype likelihoods from the supplied SAM/BAM/VCF file." +
                "It is given as a list of PLs at the fingerprinting sites.",
        oneLineSummary = "Computes a fingerprint from the supplied SAM/BAM file.",
        programGroup = DiagnosticsAndQCProgramGroup.class)

public class ExtractFingerprint extends CommandLineProgram {

    @Argument(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "Input SAM or BAM file.")
    public File INPUT;

    @Argument(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc = "Output fingerprint file (VCF).")
    public File OUTPUT;

    @Argument(shortName = "H", doc = "A file of haplotype information. The file lists a set of SNPs, optionally arranged in high-LD blocks, to be used for fingerprinting. See " +
            "https://software.broadinstitute.org/gatk/documentation/article?id=9526 for details.")
    public File HAPLOTYPE_MAP;

    @Argument(shortName = "C", doc = "A value of estimated contamination in the input. A non-zero value will cause the program to provide a better estimate of the fingerprint in the presence of contaminating reads",
            minValue = 0D, maxValue = 1D)
    public double CONTAMINATION;

    @Argument(doc = "The sample alias to associate with the resulting fingerprint. When null, <SAMPLE> is extracted from the input file and \"<SAMPLE>\" is used. " +
            "If argument EXTRACT_CONTAMINATION=true the resulting samplename will be \"<SAMPLE>-contamination\" (if not provided).", optional = true)
    public String SAMPLE_ALIAS = null;

    @Argument(doc = "SAM/BAM or VCF file of uncontaminated sample of the same individual whose information we are trying to remove.  If we are extracting the fingerprint of the contaminating sample," +
            " this should be a sample from the same individual as the contaminated sample.  If we are extracting the fingerprint of the contaminated sample, this should be a sample from the same " +
            "individual as the contaminating sample.  If the sample being removed is a tumor sample, this sample should optimally be a normal sample from the same individual.", optional = true)
    public File BACKGROUND = null;

    @Argument(doc = "Whether the sample being removed (not the sample whose fingerprint is being extracted) is a tumor sample.  This argument can only be set to true if a BACKGROUND sample is included." +
            "  In this case, the BACKGROUND sample will be assumed to be a normal sample from the same individual.  In order to avoid potential confounding due to LoH, all fingerprinting sites which genotype " +
            "as het in the BACKGROUND sample will be removed from the output fingerprint.  If this argument is set to tru and no BACKGROUND sample is provided the tool will throw an exception.", optional = true)
    public boolean REMOVED_IS_TUMOR = false;

    @Argument(doc = "The maximum number of reads to use as evidence for any given locus. This is provided as a way to limit the " +
            "effect that any given locus may have.")
    public int LOCUS_MAX_READS = 50;

    @Argument(doc = "Extract a fingerprint for the contaminating sample (instead of the contaminated sample). Setting to true changes the effect of SAMPLE_ALIAS when null. " +
            "It names the sample in the VCF <SAMPLE>-contaminant, using the SM value from the SAM header.")
    public boolean EXTRACT_CONTAMINATION = false;

    @Override
    protected boolean requiresReference() {
        return true;
    }

    private static final Log log = Log.getInstance(ExtractFingerprint.class);

    @Override
    protected int doWork() {
        IOUtil.assertFileIsReadable(INPUT);
        IOUtil.assertFileIsReadable(HAPLOTYPE_MAP);
        IOUtil.assertFileIsWritable(OUTPUT);
        IOUtil.assertFileIsReadable(referenceSequence.getReferenceFile());

        final FingerprintChecker checker = new FingerprintChecker(HAPLOTYPE_MAP);

        // if we want the contaminated fingerprint instead, we need to change the value of CONTAMINATION:
        if (!EXTRACT_CONTAMINATION) {
            CONTAMINATION = 1 - CONTAMINATION;
        }

        checker.setLocusMaxReads(LOCUS_MAX_READS);
        checker.setValidationStringency(VALIDATION_STRINGENCY);

        if (SAMPLE_ALIAS != null) {
            checker.setDefaultSampleID(SAMPLE_ALIAS);
        }

        if (BACKGROUND == null && REMOVED_IS_TUMOR) {
                throw new IllegalArgumentException("Cannot have REMOVED_IS_TUMOR true without providing a BACKGROUND sample.");
        }

        final Map<String, Fingerprint> fingerprintMap = BACKGROUND == null ?
                checker.identifyContaminant(INPUT.toPath(), CONTAMINATION) :
                checker.identifyContaminant(INPUT.toPath(), CONTAMINATION, BACKGROUND.toPath(), getCorrespondingSampleMap(INPUT, BACKGROUND), REMOVED_IS_TUMOR);

        if (fingerprintMap.size() != 1) {
            log.error("Expected exactly 1 fingerprint, found " + fingerprintMap.size());
            throw new IllegalArgumentException("Expected exactly 1 fingerprint in Input file, found " + fingerprintMap.size());
        }

        final Map.Entry<String, Fingerprint> soleEntry = fingerprintMap.entrySet().iterator().next();

        final String sampleToUse = getSampleToUse(soleEntry.getKey());

        try {
            FingerprintUtils.writeFingerPrint(soleEntry.getValue(), OUTPUT, referenceSequence.getReferenceFile(), sampleToUse, "PLs derived from " + INPUT + " using an assumed contamination of " + this.CONTAMINATION);
        } catch (Exception e) {
            log.error(e);
        }
        return 0;
    }

    private String getSampleToUse(final String fpSample) {

        if (SAMPLE_ALIAS == null) {
            return String.format("%s%s", fpSample, EXTRACT_CONTAMINATION ? "-contaminant" : "");
        } else {
            return SAMPLE_ALIAS;
        }
    }

    static Map<String, String> getCorrespondingSampleMap(final File inputFile, final File backgroundFile) {
        final String sample = getSingleSampleNameFromReadFile(inputFile);
        final String backgroundSample = CheckFingerprint.fileContainsReads(backgroundFile.toPath()) ? getSingleSampleNameFromReadFile(backgroundFile) : getSingleSampleNameFromVCFFile(backgroundFile);

        return Collections.singletonMap(backgroundSample, sample);
    }

    static String getSingleSampleNameFromReadFile(final File readFile) {
        final SamReader samReader = SamReaderFactory.makeDefault().open(readFile);
        final Set<String> samples = samReader.getFileHeader().getReadGroups().stream().map(SAMReadGroupRecord::getSample).collect(Collectors.toSet());
        if (samples.size() != 1) {
            throw new IllegalArgumentException("must have only one sample in input file " + readFile);
        }

        return samples.iterator().next();
    }

    static String getSingleSampleNameFromVCFFile(final File vcfFile) {
        final VCFFileReader vcfReader = new VCFFileReader(vcfFile, false);
        final VCFHeader header = vcfReader.getHeader();
        final Set<String> samples = new HashSet<>(header.getGenotypeSamples());
        if (samples.size() != 1) {
            throw new IllegalArgumentException("must have only one sample in input file " + vcfFile);
        }

        return samples.iterator().next();
    }

}
