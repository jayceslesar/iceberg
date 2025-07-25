/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.apache.iceberg.expressions.Expressions.alwaysTrue;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.iceberg.avro.AvroIterable;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.expressions.Evaluator;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.InclusiveMetricsEvaluator;
import org.apache.iceberg.expressions.Projections;
import org.apache.iceberg.io.CloseableGroup;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.CloseableIterator;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.metrics.ScanMetrics;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PartitionSet;

/**
 * Base reader for data and delete manifest files.
 *
 * @param <F> The Java class of files returned by this reader.
 */
public class ManifestReader<F extends ContentFile<F>> extends CloseableGroup
    implements CloseableIterable<F> {
  static final ImmutableList<String> ALL_COLUMNS = ImmutableList.of("*");

  private static final Set<String> STATS_COLUMNS =
      ImmutableSet.of(
          "value_counts",
          "null_value_counts",
          "nan_value_counts",
          "lower_bounds",
          "upper_bounds",
          "record_count");

  protected enum FileType {
    DATA_FILES(GenericDataFile.class),
    DELETE_FILES(GenericDeleteFile.class);

    private final Class<? extends StructLike> fileClass;

    FileType(Class<? extends StructLike> fileClass) {
      this.fileClass = fileClass;
    }

    private Class<? extends StructLike> fileClass() {
      return fileClass;
    }
  }

  private final InputFile file;
  private final InheritableMetadata inheritableMetadata;
  private final Long firstRowId;
  private final FileType content;
  private final PartitionSpec spec;
  private final Schema fileSchema;

  // updated by configuration methods
  private PartitionSet partitionSet = null;
  private Expression partFilter = alwaysTrue();
  private Expression rowFilter = alwaysTrue();
  private Schema fileProjection = null;
  private Collection<String> columns = null;
  private boolean caseSensitive = true;
  private ScanMetrics scanMetrics = ScanMetrics.noop();

  // lazily initialized
  private Evaluator lazyEvaluator = null;
  private InclusiveMetricsEvaluator lazyMetricsEvaluator = null;

  protected ManifestReader(
      InputFile file,
      int specId,
      Map<Integer, PartitionSpec> specsById,
      InheritableMetadata inheritableMetadata,
      FileType content) {
    this(file, specId, specsById, inheritableMetadata, null, content);
  }

  protected ManifestReader(
      InputFile file,
      int specId,
      Map<Integer, PartitionSpec> specsById,
      InheritableMetadata inheritableMetadata,
      Long firstRowId,
      FileType content) {
    Preconditions.checkArgument(
        firstRowId == null || content == FileType.DATA_FILES,
        "First row ID is not valid for delete manifests");
    this.file = file;
    this.inheritableMetadata = inheritableMetadata;
    this.firstRowId = firstRowId;
    this.content = content;

    if (specsById != null) {
      this.spec = specsById.get(specId);
    } else {
      this.spec = readPartitionSpec(file);
    }

    this.fileSchema = new Schema(DataFile.getType(spec.rawPartitionType()).fields());
  }

  private <T extends ContentFile<T>> PartitionSpec readPartitionSpec(InputFile inputFile) {
    Map<String, String> metadata = readMetadata(inputFile);

    int specId = TableMetadata.INITIAL_SPEC_ID;
    String specProperty = metadata.get("partition-spec-id");
    if (specProperty != null) {
      specId = Integer.parseInt(specProperty);
    }

    Schema schema = SchemaParser.fromJson(metadata.get("schema"));
    return PartitionSpecParser.fromJsonFields(schema, specId, metadata.get("partition-spec"));
  }

  private static <T extends ContentFile<T>> Map<String, String> readMetadata(InputFile inputFile) {
    Map<String, String> metadata;
    try {
      try (CloseableIterable<ManifestEntry<T>> headerReader =
          InternalData.read(FileFormat.AVRO, inputFile)
              .project(ManifestEntry.getSchema(Types.StructType.of()).select("status"))
              .build()) {

        if (headerReader instanceof AvroIterable) {
          metadata = ((AvroIterable<ManifestEntry<T>>) headerReader).getMetadata();
        } else {
          throw new RuntimeException(
              "Reader does not support metadata reading: " + headerReader.getClass().getName());
        }
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    return metadata;
  }

  public boolean isDeleteManifestReader() {
    return content == FileType.DELETE_FILES;
  }

  public InputFile file() {
    return file;
  }

  public Schema schema() {
    return fileSchema;
  }

  public PartitionSpec spec() {
    return spec;
  }

  public ManifestReader<F> select(Collection<String> newColumns) {
    Preconditions.checkState(
        fileProjection == null,
        "Cannot select columns using both select(String...) and project(Schema)");
    this.columns = newColumns;
    return this;
  }

  public ManifestReader<F> project(Schema newFileProjection) {
    Preconditions.checkState(
        columns == null, "Cannot select columns using both select(String...) and project(Schema)");
    this.fileProjection = newFileProjection;
    return this;
  }

  public ManifestReader<F> filterPartitions(Expression expr) {
    this.partFilter = Expressions.and(partFilter, expr);
    return this;
  }

  public ManifestReader<F> filterPartitions(PartitionSet partitions) {
    this.partitionSet = partitions;
    return this;
  }

  public ManifestReader<F> filterRows(Expression expr) {
    this.rowFilter = Expressions.and(rowFilter, expr);
    return this;
  }

  public ManifestReader<F> caseSensitive(boolean isCaseSensitive) {
    this.caseSensitive = isCaseSensitive;
    return this;
  }

  ManifestReader<F> scanMetrics(ScanMetrics newScanMetrics) {
    this.scanMetrics = newScanMetrics;
    return this;
  }

  CloseableIterable<ManifestEntry<F>> entries() {
    return entries(false /* all entries */);
  }

  private CloseableIterable<ManifestEntry<F>> entries(boolean onlyLive) {
    if (hasRowFilter() || hasPartitionFilter() || partitionSet != null) {
      Evaluator evaluator = evaluator();
      InclusiveMetricsEvaluator metricsEvaluator = metricsEvaluator();

      // ensure stats columns are present for metrics evaluation
      boolean requireStatsProjection = requireStatsProjection(rowFilter, columns);
      Collection<String> projectColumns =
          requireStatsProjection ? withStatsColumns(columns) : columns;
      CloseableIterable<ManifestEntry<F>> entries =
          open(projection(fileSchema, fileProjection, projectColumns, caseSensitive));

      return CloseableIterable.filter(
          content == FileType.DATA_FILES
              ? scanMetrics.skippedDataFiles()
              : scanMetrics.skippedDeleteFiles(),
          onlyLive ? filterLiveEntries(entries) : entries,
          entry ->
              entry != null
                  && evaluator.eval(entry.file().partition())
                  && metricsEvaluator.eval(entry.file())
                  && inPartitionSet(entry.file()));
    } else {
      CloseableIterable<ManifestEntry<F>> entries =
          open(projection(fileSchema, fileProjection, columns, caseSensitive));
      return onlyLive ? filterLiveEntries(entries) : entries;
    }
  }

  private boolean hasRowFilter() {
    return rowFilter != alwaysTrue();
  }

  private boolean hasPartitionFilter() {
    return partFilter != alwaysTrue();
  }

  private boolean inPartitionSet(F fileToCheck) {
    return partitionSet == null
        || partitionSet.contains(fileToCheck.specId(), fileToCheck.partition());
  }

  private CloseableIterable<ManifestEntry<F>> open(Schema projection) {
    FileFormat format = FileFormat.fromFileName(file.location());
    Preconditions.checkArgument(
        format != null, "Unable to determine format of manifest: %s", file.location());

    List<Types.NestedField> fields = Lists.newArrayList();
    fields.addAll(projection.asStruct().fields());
    if (projection.findField(DataFile.RECORD_COUNT.fieldId()) == null) {
      fields.add(DataFile.RECORD_COUNT);
    }
    if (projection.findField(DataFile.FIRST_ROW_ID.fieldId()) == null) {
      fields.add(DataFile.FIRST_ROW_ID);
    }
    fields.add(MetadataColumns.ROW_POSITION);

    CloseableIterable<ManifestEntry<F>> reader =
        InternalData.read(format, file)
            .project(ManifestEntry.wrapFileSchema(Types.StructType.of(fields)))
            .setRootType(GenericManifestEntry.class)
            .setCustomType(ManifestEntry.DATA_FILE_ID, content.fileClass())
            .setCustomType(DataFile.PARTITION_ID, PartitionData.class)
            .reuseContainers()
            .build();

    addCloseable(reader);

    CloseableIterable<ManifestEntry<F>> withMetadata =
        CloseableIterable.transform(reader, inheritableMetadata::apply);
    return CloseableIterable.transform(withMetadata, idAssigner(firstRowId));
  }

  CloseableIterable<ManifestEntry<F>> liveEntries() {
    return entries(true /* only live entries */);
  }

  private CloseableIterable<ManifestEntry<F>> filterLiveEntries(
      CloseableIterable<ManifestEntry<F>> entries) {
    return CloseableIterable.filter(entries, this::isLiveEntry);
  }

  private boolean isLiveEntry(ManifestEntry<F> entry) {
    return entry != null && entry.status() != ManifestEntry.Status.DELETED;
  }

  /**
   * @return an Iterator of DataFile. Makes defensive copies of files before returning
   */
  @Override
  public CloseableIterator<F> iterator() {
    boolean dropStats = dropStats(columns);
    return CloseableIterable.transform(liveEntries(), e -> e.file().copy(!dropStats)).iterator();
  }

  private static Schema projection(
      Schema schema, Schema project, Collection<String> columns, boolean caseSensitive) {
    if (columns != null) {
      if (caseSensitive) {
        return schema.select(columns);
      } else {
        return schema.caseInsensitiveSelect(columns);
      }
    } else if (project != null) {
      return project;
    }

    return schema;
  }

  private Evaluator evaluator() {
    if (lazyEvaluator == null) {
      Expression projected = Projections.inclusive(spec, caseSensitive).project(rowFilter);
      Expression finalPartFilter = Expressions.and(projected, partFilter);
      this.lazyEvaluator = new Evaluator(spec.partitionType(), finalPartFilter, caseSensitive);
    }
    return lazyEvaluator;
  }

  private InclusiveMetricsEvaluator metricsEvaluator() {
    if (lazyMetricsEvaluator == null) {
      this.lazyMetricsEvaluator =
          new InclusiveMetricsEvaluator(spec.schema(), rowFilter, caseSensitive);
    }
    return lazyMetricsEvaluator;
  }

  private static boolean requireStatsProjection(Expression rowFilter, Collection<String> columns) {
    // Make sure we have all stats columns for metrics evaluator
    return rowFilter != alwaysTrue()
        && columns != null
        && !columns.containsAll(ManifestReader.ALL_COLUMNS)
        && !columns.containsAll(STATS_COLUMNS);
  }

  static boolean dropStats(Collection<String> columns) {
    // Make sure we only drop all stats if we had projected all stats
    // We do not drop stats even if we had partially added some stats columns, except for
    // record_count column.
    // Since we don't want to keep stats map which could be huge in size just because we select
    // record_count, which
    // is a primitive type.
    if (columns != null && !columns.containsAll(ManifestReader.ALL_COLUMNS)) {
      Set<String> intersection = Sets.intersection(Sets.newHashSet(columns), STATS_COLUMNS);
      return intersection.isEmpty() || intersection.equals(Sets.newHashSet("record_count"));
    }
    return false;
  }

  static List<String> withStatsColumns(Collection<String> columns) {
    if (columns.containsAll(ManifestReader.ALL_COLUMNS)) {
      return Lists.newArrayList(columns);
    } else {
      List<String> projectColumns = Lists.newArrayList(columns);
      projectColumns.addAll(STATS_COLUMNS); // order doesn't matter
      return projectColumns;
    }
  }

  private static <F extends ContentFile<F>> Function<ManifestEntry<F>, ManifestEntry<F>> idAssigner(
      Long firstRowId) {
    if (firstRowId != null) {
      return new Function<>() {
        private long nextRowId = firstRowId;

        @Override
        public ManifestEntry<F> apply(ManifestEntry<F> entry) {
          if (entry.file() instanceof BaseFile && entry.status() != ManifestEntry.Status.DELETED) {
            BaseFile<?> file = (BaseFile<?>) entry.file();
            if (null == file.firstRowId()) {
              file.setFirstRowId(nextRowId);
              nextRowId += file.recordCount();
            }
          }

          return entry;
        }
      };
    } else {
      // data file's first_row_id is null when the manifest's first_row_id is null
      return entry -> {
        if (entry.file() instanceof BaseFile) {
          ((BaseFile<?>) entry.file()).setFirstRowId(null);
        }

        return entry;
      };
    }
  }
}
