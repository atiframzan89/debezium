/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.sqlserver;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.sqlserver.jdbc.SQLServerDriver;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.Column;
import io.debezium.relational.ColumnEditor;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.DatabaseSchema;
import io.debezium.util.Clock;

/**
 * {@link JdbcConnection} extension to be used with Microsoft SQL Server
 *
 * @author Horia Chiorean (hchiorea@redhat.com), Jiri Pechanec
 *
 */
public class SqlServerConnection extends JdbcConnection {

    public static final String SERVER_TIMEZONE_PROP_NAME = "server.timezone";
    public static final String INSTANCE_NAME = "instance";

    private static final String GET_DATABASE_NAME = "SELECT db_name()";

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerConnection.class);

    private static final String STATEMENTS_PLACEHOLDER = "#";
    private static final String GET_MAX_LSN = "SELECT sys.fn_cdc_get_max_lsn()";
    private static final String GET_MAX_TRANSACTION_LSN = "SELECT MAX(start_lsn) FROM cdc.lsn_time_mapping WHERE tran_id <> 0x00";
    private static final String GET_NTH_TRANSACTION_LSN_FROM_BEGINNING = "SELECT MAX(start_lsn) FROM (SELECT TOP (?) start_lsn FROM cdc.lsn_time_mapping WHERE tran_id <> 0x00 ORDER BY start_lsn) as next_lsns";
    private static final String GET_NTH_TRANSACTION_LSN_FROM_LAST = "SELECT MAX(start_lsn) FROM (SELECT TOP (? + 1) start_lsn FROM cdc.lsn_time_mapping WHERE start_lsn >= ? AND tran_id <> 0x00 ORDER BY start_lsn) as next_lsns";

    private static final String GET_MIN_LSN = "SELECT sys.fn_cdc_get_min_lsn('#')";
    private static final String LOCK_TABLE = "SELECT * FROM [#] WITH (TABLOCKX)";
    private static final String SQL_SERVER_VERSION = "SELECT @@VERSION AS 'SQL Server Version'";
    private static final String INCREMENT_LSN = "SELECT sys.fn_cdc_increment_lsn(?)";
    private static final String GET_ALL_CHANGES_FOR_TABLE = "SELECT *# FROM cdc.[fn_cdc_get_all_changes_#](?, ?, N'all update old') order by [__$start_lsn] ASC, [__$seqval] ASC, [__$operation] ASC";
    protected static final String LSN_TIMESTAMP_SELECT_STATEMENT = "sys.fn_cdc_map_lsn_to_time([__$start_lsn])";
    protected static final String AT_TIME_ZONE_UTC = "AT TIME ZONE 'UTC'";
    private static final String GET_LIST_OF_CDC_ENABLED_TABLES = "EXEC sys.sp_cdc_help_change_data_capture";
    private static final String GET_LIST_OF_NEW_CDC_ENABLED_TABLES = "SELECT * FROM cdc.change_tables WHERE start_lsn BETWEEN ? AND ?";
    private static final String GET_LIST_OF_KEY_COLUMNS = "SELECT * FROM cdc.index_columns WHERE object_id=?";
    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[\\]]");

    private static final int CHANGE_TABLE_DATA_COLUMN_OFFSET = 5;

    private static final String URL_PATTERN = "jdbc:sqlserver://${" + JdbcConfiguration.HOSTNAME + "}:${" + JdbcConfiguration.PORT + "};databaseName=${"
            + JdbcConfiguration.DATABASE + "}";
    private static final ConnectionFactory FACTORY = JdbcConnection.patternBasedFactory(URL_PATTERN,
            SQLServerDriver.class.getName(),
            SqlServerConnection.class.getClassLoader(),
            JdbcConfiguration.PORT.withDefault(SqlServerConnectorConfig.PORT.defaultValueAsString()));

    /**
     * actual name of the database, which could differ in casing from the database name given in the connector config.
     */
    private final String realDatabaseName;
    private final ZoneId transactionTimezone;
    private final String getAllChangesForTable;
    private final int queryFetchSize;

    private final SqlServerDefaultValueConverter defaultValueConverter;

    /**
     * Creates a new connection using the supplied configuration.
     *
     * @param config {@link Configuration} instance, may not be null.
     * @param clock the clock
     * @param sourceTimestampMode strategy for populating {@code source.ts_ms}.
     * @param valueConverters {@link SqlServerValueConverters} instance
     */
    public SqlServerConnection(Configuration config, Clock clock, SourceTimestampMode sourceTimestampMode, SqlServerValueConverters valueConverters) {
        this(config, clock, sourceTimestampMode, valueConverters, null);
    }

    /**
     * Creates a new connection using the supplied configuration.
     *
     * @param config {@link Configuration} instance, may not be null.
     * @param clock the clock
     * @param sourceTimestampMode strategy for populating {@code source.ts_ms}.
     * @param valueConverters {@link SqlServerValueConverters} instance
     * @param classLoaderSupplier class loader supplier
     */
    public SqlServerConnection(Configuration config, Clock clock, SourceTimestampMode sourceTimestampMode, SqlServerValueConverters valueConverters,
                               Supplier<ClassLoader> classLoaderSupplier) {
        super(config, FACTORY, classLoaderSupplier);
        realDatabaseName = retrieveRealDatabaseName();
        boolean supportsAtTimeZone = supportsAtTimeZone();
        transactionTimezone = retrieveTransactionTimezone(supportsAtTimeZone);
        getAllChangesForTable = GET_ALL_CHANGES_FOR_TABLE.replaceFirst(STATEMENTS_PLACEHOLDER,
                Matcher.quoteReplacement(sourceTimestampMode.lsnTimestampSelectStatement(supportsAtTimeZone)));
        defaultValueConverter = new SqlServerDefaultValueConverter(this::connection, valueConverters);
        this.queryFetchSize = config().getInteger(CommonConnectorConfig.QUERY_FETCH_SIZE);
    }

    /**
     * Returns a JDBC connection string for the current configuration.
     *
     * @return a {@code String} where the variables in {@code urlPattern} are replaced with values from the configuration
     */
    public String connectionString() {
        return connectionString(URL_PATTERN);
    }

    /**
     * @return the current largest log sequence number
     */
    public Lsn getMaxLsn() throws SQLException {
        return queryAndMap(GET_MAX_LSN, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Current maximum lsn is {}", ret);
            return ret;
        }, "Maximum LSN query must return exactly one value"));
    }

    /**
     * @return the log sequence number of the most recent transaction
     *         that isn't further than {@code maxOffset} from the beginning.
     */
    public Lsn getNthTransactionLsnFromBeginning(int maxOffset) throws SQLException {
        return prepareQueryAndMap(GET_NTH_TRANSACTION_LSN_FROM_BEGINNING, statement -> {
            statement.setInt(1, maxOffset);
        }, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Nth lsn from beginning is {}", ret);
            return ret;
        }, "Nth LSN query must return exactly one value"));
    }

    /**
     * @return the log sequence number of the most recent transaction
     *         that isn't further than {@code maxOffset} from {@code lastLsn}.
     */
    public Lsn getNthTransactionLsnFromLast(Lsn lastLsn, int maxOffset) throws SQLException {
        return prepareQueryAndMap(GET_NTH_TRANSACTION_LSN_FROM_LAST, statement -> {
            statement.setInt(1, maxOffset);
            statement.setBytes(2, lastLsn.getBinary());
        }, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Nth lsn from last is {}", ret);
            return ret;
        }, "Nth LSN query must return exactly one value"));
    }

    /**
     * @return the log sequence number of the most recent transaction.
     */
    public Lsn getMaxTransactionLsn() throws SQLException {
        return queryAndMap(GET_MAX_TRANSACTION_LSN, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Max transaction lsn is {}", ret);
            return ret;
        }, "Max transaction LSN query must return exactly one value"));
    }

    /**
     * @return the smallest log sequence number of table
     */
    public Lsn getMinLsn(String changeTableName) throws SQLException {
        String query = GET_MIN_LSN.replace(STATEMENTS_PLACEHOLDER, changeTableName);
        return queryAndMap(query, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Current minimum lsn is {}", ret);
            return ret;
        }, "Minimum LSN query must return exactly one value"));
    }

    /**
     * Provides all changes recorded by the SQL Server CDC capture process for a given table.
     *
     * @param tableId - the requested table changes
     * @param fromLsn - closed lower bound of interval of changes to be provided
     * @param toLsn  - closed upper bound of interval  of changes to be provided
     * @param consumer - the change processor
     * @throws SQLException
     */
    public void getChangesForTable(TableId tableId, Lsn fromLsn, Lsn toLsn, ResultSetConsumer consumer) throws SQLException {
        final String query = getAllChangesForTable.replace(STATEMENTS_PLACEHOLDER, cdcNameForTable(tableId));
        prepareQuery(query, statement -> {
            statement.setBytes(1, fromLsn.getBinary());
            statement.setBytes(2, toLsn.getBinary());
        }, consumer);
    }

    /**
     * Provides all changes recorder by the SQL Server CDC capture process for a set of tables.
     *
     * @param changeTables - the requested tables to obtain changes for
     * @param intervalFromLsn - closed lower bound of interval of changes to be provided
     * @param intervalToLsn  - closed upper bound of interval  of changes to be provided
     * @param consumer - the change processor
     * @throws SQLException
     */
    public void getChangesForTables(SqlServerChangeTable[] changeTables, Lsn intervalFromLsn, Lsn intervalToLsn, BlockingMultiResultSetConsumer consumer)
            throws SQLException, InterruptedException {
        final String[] queries = new String[changeTables.length];
        final StatementPreparer[] preparers = new StatementPreparer[changeTables.length];

        int idx = 0;
        for (SqlServerChangeTable changeTable : changeTables) {
            final String query = getAllChangesForTable.replace(STATEMENTS_PLACEHOLDER, changeTable.getCaptureInstance());
            queries[idx] = query;
            // If the table was added in the middle of queried buffer we need
            // to adjust from to the first LSN available
            final Lsn fromLsn = getFromLsn(changeTable, intervalFromLsn);
            LOGGER.trace("Getting changes for table {} in range[{}, {}]", changeTable, fromLsn, intervalToLsn);
            preparers[idx] = statement -> {
                if (queryFetchSize > 0) {
                    statement.setFetchSize(queryFetchSize);
                }
                statement.setBytes(1, fromLsn.getBinary());
                statement.setBytes(2, intervalToLsn.getBinary());
            };

            idx++;
        }
        prepareQuery(queries, preparers, consumer);
    }

    private Lsn getFromLsn(SqlServerChangeTable changeTable, Lsn intervalFromLsn) throws SQLException {
        Lsn fromLsn = changeTable.getStartLsn().compareTo(intervalFromLsn) > 0 ? changeTable.getStartLsn() : intervalFromLsn;
        return fromLsn.getBinary() != null ? fromLsn : getMinLsn(changeTable.getCaptureInstance());
    }

    /**
     * Obtain the next available position in the database log.
     *
     * @param lsn - LSN of the current position
     * @return LSN of the next position in the database
     * @throws SQLException
     */
    public Lsn incrementLsn(Lsn lsn) throws SQLException {
        final String query = INCREMENT_LSN;
        return prepareQueryAndMap(query, statement -> {
            statement.setBytes(1, lsn.getBinary());
        }, singleResultMapper(rs -> {
            final Lsn ret = Lsn.valueOf(rs.getBytes(1));
            LOGGER.trace("Increasing lsn from {} to {}", lsn, ret);
            return ret;
        }, "Increment LSN query must return exactly one value"));
    }

    protected Instant normalize(Timestamp timestamp) {
        Instant instant = timestamp.toInstant();

        // in case the incoming timestamp was not based on UTC, shift it as per the
        // configured timezone which must match the value used by the database
        if (!transactionTimezone.getId().equals("UTC")) {
            instant = instant.atZone(transactionTimezone)
                    .toLocalDateTime()
                    .toInstant(ZoneOffset.UTC);
        }

        return instant;
    }

    /**
     * Creates an exclusive lock for a given table.
     *
     * @param tableId to be locked
     * @throws SQLException
     */
    public void lockTable(TableId tableId) throws SQLException {
        final String lockTableStmt = LOCK_TABLE.replace(STATEMENTS_PLACEHOLDER, tableId.table());
        execute(lockTableStmt);
    }

    private String cdcNameForTable(TableId tableId) {
        return tableId.schema() + '_' + tableId.table();
    }

    public static class CdcEnabledTable {
        private final String tableId;
        private final String captureName;
        private final Lsn fromLsn;

        private CdcEnabledTable(String tableId, String captureName, Lsn fromLsn) {
            this.tableId = tableId;
            this.captureName = captureName;
            this.fromLsn = fromLsn;
        }

        public String getTableId() {
            return tableId;
        }

        public String getCaptureName() {
            return captureName;
        }

        public Lsn getFromLsn() {
            return fromLsn;
        }
    }

    public Set<SqlServerChangeTable> listOfChangeTables() throws SQLException {
        final String query = GET_LIST_OF_CDC_ENABLED_TABLES;

        return queryAndMap(query, rs -> {
            final Set<SqlServerChangeTable> changeTables = new HashSet<>();
            while (rs.next()) {
                changeTables.add(
                        new SqlServerChangeTable(
                                new TableId(realDatabaseName, rs.getString(1), rs.getString(2)),
                                rs.getString(3),
                                rs.getInt(4),
                                Lsn.valueOf(rs.getBytes(6)),
                                Lsn.valueOf(rs.getBytes(7)),
                                Arrays.asList(BRACKET_PATTERN.matcher(Optional.ofNullable(rs.getString(15)).orElse(""))
                                        .replaceAll("").split(", "))));
            }
            return changeTables;
        });
    }

    public Set<SqlServerChangeTable> listOfNewChangeTables(Lsn fromLsn, Lsn toLsn) throws SQLException {
        final String query = GET_LIST_OF_NEW_CDC_ENABLED_TABLES;

        return prepareQueryAndMap(query,
                ps -> {
                    ps.setBytes(1, fromLsn.getBinary());
                    ps.setBytes(2, toLsn.getBinary());
                },
                rs -> {
                    final Set<SqlServerChangeTable> changeTables = new HashSet<>();
                    while (rs.next()) {
                        changeTables.add(new SqlServerChangeTable(
                                rs.getString(4),
                                rs.getInt(1),
                                Lsn.valueOf(rs.getBytes(5)),
                                Lsn.valueOf(rs.getBytes(6))));
                    }
                    return changeTables;
                });
    }

    public Table getTableSchemaFromTable(SqlServerChangeTable changeTable) throws SQLException {
        final DatabaseMetaData metadata = connection().getMetaData();

        List<Column> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(
                realDatabaseName,
                changeTable.getSourceTableId().schema(),
                changeTable.getSourceTableId().table(),
                null)) {
            while (rs.next()) {
                readTableColumn(rs, changeTable.getSourceTableId(), null).ifPresent(ce -> {
                    // Filter out columns not included in the change table.
                    if (changeTable.getCapturedColumns().contains(ce.name())) {
                        columns.add(ce.create());
                    }
                });
            }
        }

        final List<String> pkColumnNames = readPrimaryKeyOrUniqueIndexNames(metadata, changeTable.getSourceTableId()).stream()
                .filter(column -> changeTable.getCapturedColumns().contains(column))
                .collect(Collectors.toList());
        Collections.sort(columns);
        return Table.editor()
                .tableId(changeTable.getSourceTableId())
                .addColumns(columns)
                .setPrimaryKeyNames(pkColumnNames)
                .create();
    }

    public Table getTableSchemaFromChangeTable(SqlServerChangeTable changeTable) throws SQLException {
        final DatabaseMetaData metadata = connection().getMetaData();
        final TableId changeTableId = changeTable.getChangeTableId();

        List<ColumnEditor> columnEditors = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(realDatabaseName, changeTableId.schema(), changeTableId.table(), null)) {
            while (rs.next()) {
                readTableColumn(rs, changeTableId, null).ifPresent(columnEditors::add);
            }
        }

        // The first 5 columns and the last column of the change table are CDC metadata
        final List<Column> columns = columnEditors.subList(CHANGE_TABLE_DATA_COLUMN_OFFSET, columnEditors.size() - 1).stream()
                .map(c -> c.position(c.position() - CHANGE_TABLE_DATA_COLUMN_OFFSET).create())
                .collect(Collectors.toList());

        final List<String> pkColumnNames = new ArrayList<>();
        prepareQuery(GET_LIST_OF_KEY_COLUMNS, ps -> ps.setInt(1, changeTable.getChangeTableObjectId()), rs -> {
            while (rs.next()) {
                pkColumnNames.add(rs.getString(2));
            }
        });
        Collections.sort(columns);
        return Table.editor()
                .tableId(changeTable.getSourceTableId())
                .addColumns(columns)
                .setPrimaryKeyNames(pkColumnNames)
                .create();
    }

    public String getNameOfChangeTable(String captureName) {
        return captureName + "_CT";
    }

    public String getRealDatabaseName() {
        return realDatabaseName;
    }

    private ZoneId retrieveTransactionTimezone(boolean supportsAtTimeZone) {
        final String serverTimezoneConfig = config().getString(SERVER_TIMEZONE_PROP_NAME);

        if (supportsAtTimeZone) {
            if (serverTimezoneConfig != null) {
                LOGGER.warn("The '{}' option should not be specified with SQL Server 2016 and newer", SERVER_TIMEZONE_PROP_NAME);
            }
        }
        else {
            if (serverTimezoneConfig == null) {
                LOGGER.warn(
                        "The '{}' option should be specified to avoid incorrect timestamp values in case of different timezones between the database server and this connector's JVM.",
                        SERVER_TIMEZONE_PROP_NAME);
            }
        }

        // Assuming UTC to be used for the ts_ms TIMESTAMP column
        // In case AT TIME ZONE is supported, UTC is what we'll request;
        // Otherwise, UTC is as good as any other guess
        return serverTimezoneConfig == null ? ZoneId.of("UTC") : ZoneId.of(serverTimezoneConfig, ZoneId.SHORT_IDS);
    }

    private String retrieveRealDatabaseName() {
        try {
            return queryAndMap(
                    GET_DATABASE_NAME,
                    singleResultMapper(rs -> rs.getString(1), "Could not retrieve database name"));
        }
        catch (SQLException e) {
            throw new RuntimeException("Couldn't obtain database name", e);
        }
    }

    /**
     * SELECT ... AT TIME ZONE only works on SQL Server 2016 and newer.
     */
    private boolean supportsAtTimeZone() {
        try {
            // Always expect the support if database is not standalone SQL Server, e.g. Azure
            return getSqlServerVersion().orElse(Integer.MAX_VALUE) > 2016;
        }
        catch (Exception e) {
            LOGGER.error("Couldn't obtain database server version; assuming 'AT TIME ZONE' is not supported.", e);
            return false;
        }
    }

    private Optional<Integer> getSqlServerVersion() {
        try {
            // As per https://www.mssqltips.com/sqlservertip/1140/how-to-tell-what-sql-server-version-you-are-running/
            // Always beginning with 'Microsoft SQL Server NNNN' but only in case SQL Server is standalone
            String version = queryAndMap(
                    SQL_SERVER_VERSION,
                    singleResultMapper(rs -> rs.getString(1), "Could not obtain SQL Server version"));
            if (!version.startsWith("Microsoft SQL Server ")) {
                return Optional.empty();
            }
            return Optional.of(Integer.valueOf(version.substring(21, 25)));
        }
        catch (Exception e) {
            throw new RuntimeException("Couldn't obtain database server version", e);
        }
    }

    @Override
    protected Optional<Object> getDefaultValue(Column column, String defaultValue) {
        return defaultValueConverter
                .parseDefaultValue(column, defaultValue);
    }

    @Override
    protected boolean isTableUniqueIndexIncluded(String indexName, String columnName) {
        // SQL Server provides indices also without index name
        // so we need to ignore them
        return indexName != null;
    }

    @Override
    public <T extends DatabaseSchema<TableId>> Object getColumnValue(ResultSet rs, int columnIndex, Column column,
                                                                     Table table, T schema)
            throws SQLException {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnType = metaData.getColumnType(columnIndex);

        if (columnType == Types.TIME) {
            return rs.getTimestamp(columnIndex);
        }
        else {
            return super.getColumnValue(rs, columnIndex, column, table, schema);
        }
    }

    @Override
    public String buildSelectWithRowLimits(TableId tableId, int limit, String projection, Optional<String> condition,
                                           String orderBy) {
        final StringBuilder sql = new StringBuilder("SELECT TOP ");
        sql
                .append(limit)
                .append(' ')
                .append(projection)
                .append(" FROM ");
        sql.append(quotedTableIdString(tableId));
        if (condition.isPresent()) {
            sql
                    .append(" WHERE ")
                    .append(condition.get());
        }
        sql
                .append(" ORDER BY ")
                .append(orderBy);
        return sql.toString();
    }

    @Override
    public String quotedTableIdString(TableId tableId) {
        return "[" + tableId.schema() + "].[" + tableId.table() + "]";
    }
}
