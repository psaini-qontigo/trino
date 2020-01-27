/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive;

import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.plugin.hive.LocationService.WriteInfo;
import io.prestosql.plugin.hive.PartitionUpdate.UpdateMode;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.classloader.ThreadContextClassLoader;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.procedure.Procedure;
import io.prestosql.spi.procedure.Procedure.Argument;
import io.prestosql.spi.type.ArrayType;
import org.apache.hadoop.hive.common.FileUtils;

import javax.inject.Inject;
import javax.inject.Provider;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.prestosql.spi.StandardErrorCode.INVALID_PROCEDURE_ARGUMENT;
import static io.prestosql.spi.block.MethodHandleUtil.methodHandle;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;

public class CreateEmptyPartitionProcedure
        implements Provider<Procedure>
{
    private static final MethodHandle CREATE_EMPTY_PARTITION = methodHandle(
            CreateEmptyPartitionProcedure.class,
            "createEmptyPartition",
            ConnectorSession.class,
            String.class,
            String.class,
            List.class,
            List.class);

    private final Supplier<TransactionalMetadata> hiveMetadataFactory;
    private final HiveMetastoreClosure metastore;
    private final LocationService locationService;
    private final JsonCodec<PartitionUpdate> partitionUpdateJsonCodec;

    @Inject
    public CreateEmptyPartitionProcedure(Supplier<TransactionalMetadata> hiveMetadataFactory, HiveMetastore metastore, LocationService locationService, JsonCodec<PartitionUpdate> partitionUpdateCodec)
    {
        this.hiveMetadataFactory = requireNonNull(hiveMetadataFactory, "hiveMetadataFactory is null");
        this.metastore = new HiveMetastoreClosure(requireNonNull(metastore, "metastore is null"));
        this.locationService = requireNonNull(locationService, "locationService is null");
        this.partitionUpdateJsonCodec = requireNonNull(partitionUpdateCodec, "partitionUpdateCodec is null");
    }

    @Override
    public Procedure get()
    {
        return new Procedure(
                "system",
                "create_empty_partition",
                ImmutableList.of(
                        new Argument("schema_name", VARCHAR),
                        new Argument("table_name", VARCHAR),
                        new Argument("partition_columns", new ArrayType(VARCHAR)),
                        new Argument("partition_values", new ArrayType(VARCHAR))),
                CREATE_EMPTY_PARTITION.bindTo(this));
    }

    public void createEmptyPartition(ConnectorSession session, String schema, String table, List<String> partitionColumnNames, List<String> partitionValues)
    {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(getClass().getClassLoader())) {
            doCreateEmptyPartition(session, schema, table, partitionColumnNames, partitionValues);
        }
    }

    private void doCreateEmptyPartition(ConnectorSession session, String schemaName, String tableName, List<String> partitionColumnNames, List<String> partitionValues)
    {
        TransactionalMetadata hiveMetadata = hiveMetadataFactory.get();
        HiveTableHandle tableHandle = (HiveTableHandle) hiveMetadata.getTableHandle(session, new SchemaTableName(schemaName, tableName));

        List<String> actualPartitionColumnNames = tableHandle.getPartitionColumns().stream()
                .map(HiveColumnHandle::getName)
                .collect(toImmutableList());

        if (!Objects.equals(partitionColumnNames, actualPartitionColumnNames)) {
            throw new PrestoException(INVALID_PROCEDURE_ARGUMENT, "Provided partition column names do not match actual partition column names: " + actualPartitionColumnNames);
        }

        if (metastore.getPartition(new HiveIdentity(session), schemaName, tableName, partitionValues).isPresent()) {
            throw new PrestoException(ALREADY_EXISTS, "Partition already exists");
        }
        HiveInsertTableHandle hiveInsertTableHandle = (HiveInsertTableHandle) hiveMetadata.beginInsert(session, tableHandle);
        String partitionName = FileUtils.makePartName(actualPartitionColumnNames, partitionValues);

        WriteInfo writeInfo = locationService.getPartitionWriteInfo(hiveInsertTableHandle.getLocationHandle(), Optional.empty(), partitionName);
        Slice serializedPartitionUpdate = Slices.wrappedBuffer(
                partitionUpdateJsonCodec.toJsonBytes(
                        new PartitionUpdate(
                                partitionName,
                                UpdateMode.NEW,
                                writeInfo.getWritePath(),
                                writeInfo.getTargetPath(),
                                ImmutableList.of(),
                                0,
                                0,
                                0)));

        hiveMetadata.finishInsert(
                session,
                hiveInsertTableHandle,
                ImmutableList.of(serializedPartitionUpdate),
                ImmutableList.of());
        hiveMetadata.commit();
    }
}
