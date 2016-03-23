package org.seqdoop.hadoop_bam;

import hbparquet.hadoop.util.ContextUtil;
import htsjdk.samtools.BAMIndex;
import htsjdk.samtools.BAMIndexer;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordSetBuilder;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.Interval;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class TestBAMInputFormat {
  private String input;
  private TaskAttemptContext taskAttemptContext;
  private JobContext jobContext;

  @Before
  public void setup() throws Exception {
    input = writeNameSortedBamFile().getAbsolutePath();
  }

  private File writeNameSortedBamFile() throws IOException {
    SAMRecordSetBuilder samRecordSetBuilder =
        new SAMRecordSetBuilder(true, SAMFileHeader.SortOrder.queryname);
    for (int i = 0; i < 1000; i++) {
      int chr = 20;
      int start1 = (i + 1) * 1000;
      int start2 = start1 + 100;
      samRecordSetBuilder.addPair(String.format("test-read-%03d", i), chr, start1,
          start2);
    }

    final File bamFile = File.createTempFile("test", ".bam");
    bamFile.deleteOnExit();
    SAMFileHeader samHeader = samRecordSetBuilder.getHeader();
    final SAMFileWriter bamWriter = new SAMFileWriterFactory()
        .makeSAMOrBAMWriter(samHeader, true, bamFile);
    for (final SAMRecord rec : samRecordSetBuilder.getRecords()) {
      bamWriter.addAlignment(rec);
    }
    bamWriter.close();

    // create BAM index
    SamReader samReader = SamReaderFactory.makeDefault()
        .enable(SamReaderFactory.Option.INCLUDE_SOURCE_IN_RECORDS)
        .open(bamFile);
    BAMIndexer.createIndex(samReader, new File(bamFile.getAbsolutePath() +
        BAMIndex.BAMIndexSuffix));

    return bamFile;
  }

  private void completeSetup(boolean keepPairedReadsTogether, List<Interval> intervals) {
    Configuration conf = new Configuration();
    conf.set("mapred.input.dir", "file://" + input);
    conf.setBoolean(BAMInputFormat.KEEP_PAIRED_READS_TOGETHER_PROPERTY,
        keepPairedReadsTogether);
    if (intervals != null) {
      BAMInputFormat.setIntervals(conf, intervals);
    }
    taskAttemptContext = ContextUtil.newTaskAttemptContext(conf, mock(TaskAttemptID.class));
    jobContext = ContextUtil.newJobContext(conf, taskAttemptContext.getJobID());
  }

  @Test
  public void testNoReadsInFirstSplit() throws Exception {
    Configuration conf = new Configuration();
    String bam = getClass().getClassLoader().getResource("no-reads-in-first-split.bam").getFile();
    conf.set("mapred.input.dir", bam);
    taskAttemptContext = ContextUtil.newTaskAttemptContext(conf, mock(TaskAttemptID.class));
    jobContext = ContextUtil.newJobContext(conf, taskAttemptContext.getJobID());
    // throws IOException: 'file:/.../no-reads-in-first-split.bam': no reads in first split: bad BAM file or tiny split size?
    //jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
  }

  @Test
  public void testDontKeepPairedReadsTogether() throws Exception {
    completeSetup(false, null);
    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
    assertEquals(2, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    List<SAMRecord> split1Records = getSAMRecordsFromSplit(inputFormat, splits.get(1));
    assertEquals(1629, split0Records.size());
    assertEquals(371, split1Records.size());
    SAMRecord lastRecordOfSplit0 = split0Records.get(split0Records.size() - 1);
    SAMRecord firstRecordOfSplit1 = split1Records.get(0);
    assertEquals(lastRecordOfSplit0.getReadName(), firstRecordOfSplit1.getReadName());
    assertTrue(lastRecordOfSplit0.getFirstOfPairFlag());
    assertTrue(firstRecordOfSplit1.getSecondOfPairFlag());
  }

  @Test
  public void testKeepPairedReadsTogether() throws Exception {
    completeSetup(true, null);
    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
    assertEquals(2, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    List<SAMRecord> split1Records = getSAMRecordsFromSplit(inputFormat, splits.get(1));
    assertEquals(1630, split0Records.size());
    assertEquals(370, split1Records.size());
    SAMRecord lastRecordOfSplit0 = split0Records.get(split0Records.size() - 1);
    SAMRecord firstRecordOfSplit1 = split1Records.get(0);
    assertNotEquals(lastRecordOfSplit0.getReadName(), firstRecordOfSplit1.getReadName());
  }

  @Test
  public void testIntervals() throws Exception {
    List<Interval> intervals = new ArrayList<Interval>();
    intervals.add(new Interval("chr21", 5000, 9999));
    intervals.add(new Interval("chr21", 20000, 22999));

    completeSetup(false, intervals);

    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
    assertEquals(1, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    assertEquals(16, split0Records.size());
  }

  @Test
  public void testIntervalCoveringWholeChromosome() throws Exception {
    List<Interval> intervals = new ArrayList<Interval>();
    intervals.add(new Interval("chr21", 1, 1000135));

    completeSetup(false, intervals);

    jobContext.getConfiguration().setInt(FileInputFormat.SPLIT_MAXSIZE, 40000);
    BAMInputFormat inputFormat = new BAMInputFormat();
    List<InputSplit> splits = inputFormat.getSplits(jobContext);
    assertEquals(2, splits.size());
    List<SAMRecord> split0Records = getSAMRecordsFromSplit(inputFormat, splits.get(0));
    List<SAMRecord> split1Records = getSAMRecordsFromSplit(inputFormat, splits.get(1));
    assertEquals(1629, split0Records.size());
    assertEquals(371, split1Records.size());
  }

  private List<SAMRecord> getSAMRecordsFromSplit(BAMInputFormat inputFormat,
      InputSplit split) throws Exception {
    RecordReader<LongWritable, SAMRecordWritable> reader = inputFormat
        .createRecordReader(split, taskAttemptContext);
    reader.initialize(split, taskAttemptContext);

    List<SAMRecord> records = new ArrayList<SAMRecord>();
    while (reader.nextKeyValue()) {
      SAMRecord r = reader.getCurrentValue().get();
      records.add(r);
    }
    return records;
  }
}
