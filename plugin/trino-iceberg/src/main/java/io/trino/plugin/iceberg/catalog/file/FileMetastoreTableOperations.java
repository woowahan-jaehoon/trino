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
package io.trino.plugin.iceberg.catalog.file;

import io.trino.plugin.hive.authentication.HiveIdentity;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.PrincipalPrivileges;
import io.trino.plugin.hive.metastore.Table;
import io.trino.plugin.iceberg.catalog.AbstractMetastoreTableOperations;
import io.trino.spi.connector.ConnectorSession;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.io.FileIO;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static io.trino.plugin.hive.metastore.MetastoreUtil.buildInitialPrivilegeSet;
import static io.trino.plugin.hive.metastore.PrincipalPrivileges.NO_PRIVILEGES;

@NotThreadSafe
public class FileMetastoreTableOperations
        extends AbstractMetastoreTableOperations
{
    public FileMetastoreTableOperations(
            FileIO fileIo,
            HiveMetastore metastore,
            ConnectorSession session,
            String database,
            String table,
            Optional<String> owner,
            Optional<String> location)
    {
        super(fileIo, metastore, session, database, table, owner, location);
    }

    @Override
    protected void commitToExistingTable(TableMetadata base, TableMetadata metadata)
    {
        String newMetadataLocation = writeNewMetadata(metadata, version + 1);

        Table table;
        try {
            Table currentTable = getTable();

            checkState(currentMetadataLocation != null, "No current metadata location for existing table");
            String metadataLocation = currentTable.getParameters().get(METADATA_LOCATION);
            if (!currentMetadataLocation.equals(metadataLocation)) {
                throw new CommitFailedException("Metadata location [%s] is not same as table metadata location [%s] for %s",
                        currentMetadataLocation, metadataLocation, getSchemaTableName());
            }

            table = Table.builder(currentTable)
                    .setDataColumns(toHiveColumns(metadata.schema().columns()))
                    .withStorage(storage -> storage.setLocation(metadata.location()))
                    .setParameter(METADATA_LOCATION, newMetadataLocation)
                    .setParameter(PREVIOUS_METADATA_LOCATION, currentMetadataLocation)
                    .build();
        }
        catch (RuntimeException e) {
            try {
                io().deleteFile(newMetadataLocation);
            }
            catch (RuntimeException ex) {
                e.addSuppressed(ex);
            }
            throw e;
        }

        // todo privileges should not be replaced for an alter
        PrincipalPrivileges privileges = owner.isEmpty() && table.getOwner().isPresent() ? NO_PRIVILEGES : buildInitialPrivilegeSet(table.getOwner().get());
        HiveIdentity identity = new HiveIdentity(session);
        metastore.replaceTable(identity, database, tableName, table, privileges);
    }
}
