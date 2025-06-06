/*
 * Copyright (2023) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.defaults.internal.parquet;

import static io.delta.kernel.defaults.internal.parquet.ParquetFilterUtils.toParquetFilter;
import static io.delta.kernel.internal.util.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetRecordReaderWrapper;
import org.apache.parquet.hadoop.api.InitContext;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.schema.MessageType;

public class ParquetFileReader {
  private final FileIO fileIO;
  private final int maxBatchSize;

  public ParquetFileReader(FileIO fileIO) {
    this.fileIO = requireNonNull(fileIO, "fileIO is null");
    this.maxBatchSize =
        fileIO
            .getConf("delta.kernel.default.parquet.reader.batch-size")
            .map(Integer::valueOf)
            .orElse(1024);
    checkArgument(maxBatchSize > 0, "invalid Parquet reader batch size: %s", maxBatchSize);
  }

  public CloseableIterator<ColumnarBatch> read(
      FileStatus fileStatus, StructType schema, Optional<Predicate> predicate) {

    final boolean hasRowIndexCol =
        schema.indexOf(StructField.METADATA_ROW_INDEX_COLUMN_NAME) >= 0
            && schema.get(StructField.METADATA_ROW_INDEX_COLUMN_NAME).isMetadataColumn();

    return new CloseableIterator<ColumnarBatch>() {
      private final BatchReadSupport readSupport = new BatchReadSupport(maxBatchSize, schema);
      private ParquetRecordReaderWrapper<Object> reader;
      private boolean hasNotConsumedNextElement;

      @Override
      public void close() throws IOException {
        Utils.closeCloseables(reader);
      }

      @Override
      public boolean hasNext() {
        initParquetReaderIfRequired();
        try {
          if (hasNotConsumedNextElement) {
            return true;
          }

          hasNotConsumedNextElement = reader.nextKeyValue() && reader.getCurrentValue() != null;
          return hasNotConsumedNextElement;
        } catch (IOException | InterruptedException ex) {
          throw new KernelEngineException(
              "Error reading Parquet file: " + fileStatus.getPath(), ex);
        }
      }

      @Override
      public ColumnarBatch next() {
        if (!hasNotConsumedNextElement) {
          throw new NoSuchElementException();
        }
        int batchSize = 0;
        do {
          hasNotConsumedNextElement = false;
          // hasNext reads to row to confirm there is a next element.
          // get the row index only if required by the read schema
          long rowIndex = hasRowIndexCol ? reader.getCurrentRowIndex() : -1;
          readSupport.finalizeCurrentRow(rowIndex);
          batchSize++;
        } while (batchSize < maxBatchSize && hasNext());

        return readSupport.getDataAsColumnarBatch(batchSize);
      }

      private void initParquetReaderIfRequired() {
        if (reader == null) {
          org.apache.parquet.hadoop.ParquetFileReader fileReader = null;
          try {
            InputFile inputFile = fileIO.newInputFile(fileStatus.getPath(), fileStatus.getSize());

            // We need physical schema in order to construct a filter that can be
            // pushed into the `parquet-mr` reader. For that reason read the footer
            // in advance.
            org.apache.parquet.io.InputFile parquetInputFile =
                ParquetIOUtils.createParquetInputFile(inputFile);

            ParquetMetadata footer =
                org.apache.parquet.hadoop.ParquetFileReader.readFooter(
                    parquetInputFile, ParquetMetadataConverter.NO_FILTER);

            MessageType parquetSchema = footer.getFileMetaData().getSchema();
            Optional<FilterPredicate> parquetPredicate =
                predicate.flatMap(predicate -> toParquetFilter(parquetSchema, predicate));

            // Disable the record level filtering as the `parquet-mr` evaluates
            // the filter once the entire record has been materialized. Instead,
            // we use the predicate to prune the row groups which is more efficient.
            // In the future, we can consider using the record level filtering if a
            // native Parquet reader is implemented in Kernel default module.
            ParquetReadOptions readOptions =
                ParquetReadOptions.builder()
                    .useRecordFilter(false)
                    .useStatsFilter(true) // only enable the row group level filtering
                    .useBloomFilter(false)
                    .useDictionaryFilter(false)
                    .useColumnIndexFilter(false)
                    .withRecordFilter(
                        parquetPredicate.map(FilterCompat::get).orElse(FilterCompat.NOOP))
                    .build();

            // Pass the already read footer to the reader to avoid reading it again.
            // TODO: We can avoid reading the footer again if we can pass the footer, but there is
            // no API to do that in the current version of parquet-mr which takes InputFile
            // as input.
            fileReader =
                org.apache.parquet.hadoop.ParquetFileReader.open(parquetInputFile, readOptions);
            reader = new ParquetRecordReaderWrapper<>(readSupport);
            reader.initialize(fileReader, readOptions);
          } catch (IOException e) {
            Utils.closeCloseablesSilently(fileReader, reader);
            throw new KernelEngineException(
                "Error reading Parquet file: " + fileStatus.getPath(), e);
          }
        }
      }
    };
  }

  /**
   * Implement a {@link ReadSupport} that will collect the data for each row and return as a {@link
   * ColumnarBatch}.
   */
  public static class BatchReadSupport extends ReadSupport<Object> {
    private final int maxBatchSize;
    private final StructType readSchema;
    private RowRecordCollector rowRecordCollector;

    public BatchReadSupport(int maxBatchSize, StructType readSchema) {
      this.maxBatchSize = maxBatchSize;
      this.readSchema = requireNonNull(readSchema, "readSchema is not null");
    }

    @Override
    public ReadContext init(InitContext context) {
      return new ReadContext(ParquetSchemaUtils.pruneSchema(context.getFileSchema(), readSchema));
    }

    @Override
    public RecordMaterializer<Object> prepareForRead(
        Configuration configuration,
        Map<String, String> keyValueMetaData,
        MessageType fileSchema,
        ReadContext readContext) {
      rowRecordCollector = new RowRecordCollector(maxBatchSize, readSchema, fileSchema);
      return rowRecordCollector;
    }

    public ColumnarBatch getDataAsColumnarBatch(int batchSize) {
      return rowRecordCollector.getDataAsColumnarBatch(batchSize);
    }

    /** @param fileRowIndex the file row index of the row just processed. */
    public void finalizeCurrentRow(long fileRowIndex) {
      rowRecordCollector.finalizeCurrentRow(fileRowIndex);
    }
  }

  /**
   * Collects the records given by the Parquet reader as columnar data. Parquet reader allows
   * reading data row by row, but {@link ParquetFileReader} wants to expose the data as a columnar
   * batch. Parquet reader takes an implementation of {@link RecordMaterializer} to which it gives
   * data for each column one row at a time. This {@link RecordMaterializer} implementation collects
   * the column values for multiple rows and returns a {@link ColumnarBatch} at the end.
   */
  public static class RowRecordCollector extends RecordMaterializer<Object> {
    private static final Object FAKE_ROW_RECORD = new Object();
    private final RowColumnReader rowRecordGroupConverter;

    public RowRecordCollector(int maxBatchSize, StructType readSchema, MessageType fileSchema) {
      this.rowRecordGroupConverter = new RowColumnReader(maxBatchSize, readSchema, fileSchema);
    }

    @Override
    public void skipCurrentRecord() {
      super.skipCurrentRecord();
    }

    /**
     * Return a fake object. This is not used by {@link ParquetFileReader}, instead {@link
     * #getDataAsColumnarBatch}} once a sufficient number of rows are collected.
     */
    @Override
    public Object getCurrentRecord() {
      return FAKE_ROW_RECORD;
    }

    @Override
    public GroupConverter getRootConverter() {
      return rowRecordGroupConverter;
    }

    /** Return the data collected so far as a {@link ColumnarBatch}. */
    public ColumnarBatch getDataAsColumnarBatch(int batchSize) {
      return rowRecordGroupConverter.getDataAsColumnarBatch(batchSize);
    }

    /**
     * Finalize the current row.
     *
     * @param fileRowIndex the file row index of the row just processed
     */
    public void finalizeCurrentRow(long fileRowIndex) {
      rowRecordGroupConverter.finalizeCurrentRow(fileRowIndex);
    }
  }
}
