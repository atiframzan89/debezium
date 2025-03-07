/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

import static org.fest.assertions.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;

import io.debezium.connector.oracle.logminer.parser.LogMinerDmlParser;
import io.debezium.connector.oracle.logminer.valueholder.LogMinerDmlEntry;
import io.debezium.doc.FixFor;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;

/**
 * @author Chris Cranford
 */
public class LogMinerDmlParserTest {

    private LogMinerDmlParser fastDmlParser;

    @Before
    public void beforeEach() throws Exception {
        // Create LogMinerDmlParser
        fastDmlParser = new LogMinerDmlParser();
    }

    // Oracle's generated SQL avoids common spacing patterns such as spaces between column values or values
    // in an insert statement and is explicit about spacing and commas with SET and WHERE clauses. As of
    // now the parser expects this explicit spacing usage.

    @Test
    @FixFor("DBZ-3078")
    public void testParsingInsert() throws Exception {
        final Table table = Table.editor()
                .tableId(TableId.parse("DEBEZIUM.TEST"))
                .addColumn(Column.editor().name("ID").create())
                .addColumn(Column.editor().name("NAME").create())
                .addColumn(Column.editor().name("TS").create())
                .addColumn(Column.editor().name("UT").create())
                .addColumn(Column.editor().name("DATE").create())
                .addColumn(Column.editor().name("UT2").create())
                .addColumn(Column.editor().name("C1").create())
                .addColumn(Column.editor().name("C2").create())
                .addColumn(Column.editor().name("UNUSED").create())
                .create();

        String sql = "insert into \"DEBEZIUM\".\"TEST\"(\"ID\",\"NAME\",\"TS\",\"UT\",\"DATE\",\"UT2\",\"C1\",\"C2\") values " +
                "('1','Acme',TO_TIMESTAMP('2020-02-01 00:00:00.'),Unsupported Type," +
                "TO_DATE('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS'),Unsupported Type,NULL,NULL);";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.INSERT);
        assertThat(entry.getOldValues()).isEmpty();
        assertThat(entry.getNewValues()).hasSize(9);
        assertThat(entry.getNewValues()[0]).isEqualTo("1");
        assertThat(entry.getNewValues()[1]).isEqualTo("Acme");
        assertThat(entry.getNewValues()[2]).isEqualTo("TO_TIMESTAMP('2020-02-01 00:00:00.')");
        assertThat(entry.getNewValues()[3]).isNull();
        assertThat(entry.getNewValues()[4]).isEqualTo("TO_DATE('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')");
        assertThat(entry.getNewValues()[5]).isNull();
        assertThat(entry.getNewValues()[6]).isNull();
        assertThat(entry.getNewValues()[7]).isNull();
        assertThat(entry.getNewValues()[8]).isNull();
    }

    @Test
    @FixFor("DBZ-3078")
    public void testParsingUpdate() throws Exception {
        final Table table = Table.editor()
                .tableId(TableId.parse("DEBEZIUM.TEST"))
                .addColumn(Column.editor().name("ID").create())
                .addColumn(Column.editor().name("NAME").create())
                .addColumn(Column.editor().name("TS").create())
                .addColumn(Column.editor().name("UT").create())
                .addColumn(Column.editor().name("DATE").create())
                .addColumn(Column.editor().name("UT2").create())
                .addColumn(Column.editor().name("C1").create())
                .addColumn(Column.editor().name("IS").create())
                .addColumn(Column.editor().name("IS2").create())
                .addColumn(Column.editor().name("UNUSED").create())
                .create();

        String sql = "update \"DEBEZIUM\".\"TEST\" " +
                "set \"NAME\" = 'Bob', \"TS\" = TO_TIMESTAMP('2020-02-02 00:00:00.'), \"UT\" = Unsupported Type, " +
                "\"DATE\" = TO_DATE('2020-02-02 00:00:00', 'YYYY-MM-DD HH24:MI:SS'), \"UT2\" = Unsupported Type, " +
                "\"C1\" = NULL where \"ID\" = '1' and \"NAME\" = 'Acme' and \"TS\" = TO_TIMESTAMP('2020-02-01 00:00:00.') and " +
                "\"UT\" = Unsupported Type and \"DATE\" = TO_DATE('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') and " +
                "\"UT2\" = Unsupported Type and \"C1\" = NULL and \"IS\" IS NULL and \"IS2\" IS NULL;";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.UPDATE);
        assertThat(entry.getOldValues()).hasSize(10);
        assertThat(entry.getOldValues()[0]).isEqualTo("1");
        assertThat(entry.getOldValues()[1]).isEqualTo("Acme");
        assertThat(entry.getOldValues()[2]).isEqualTo("TO_TIMESTAMP('2020-02-01 00:00:00.')");
        assertThat(entry.getOldValues()[3]).isNull();
        assertThat(entry.getOldValues()[4]).isEqualTo("TO_DATE('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')");
        assertThat(entry.getOldValues()[5]).isNull();
        assertThat(entry.getOldValues()[6]).isNull();
        assertThat(entry.getOldValues()[7]).isNull();
        assertThat(entry.getOldValues()[8]).isNull();
        assertThat(entry.getOldValues()[9]).isNull();
        assertThat(entry.getNewValues()).hasSize(10);
        assertThat(entry.getNewValues()[0]).isEqualTo("1");
        assertThat(entry.getNewValues()[1]).isEqualTo("Bob");
        assertThat(entry.getNewValues()[2]).isEqualTo("TO_TIMESTAMP('2020-02-02 00:00:00.')");
        assertThat(entry.getNewValues()[3]).isNull();
        assertThat(entry.getNewValues()[4]).isEqualTo("TO_DATE('2020-02-02 00:00:00', 'YYYY-MM-DD HH24:MI:SS')");
        assertThat(entry.getNewValues()[5]).isNull();
        assertThat(entry.getNewValues()[6]).isNull();
        assertThat(entry.getNewValues()[7]).isNull();
        assertThat(entry.getNewValues()[8]).isNull();
        assertThat(entry.getNewValues()[9]).isNull();
    }

    @Test
    @FixFor("DBZ-3078")
    public void testParsingDelete() throws Exception {
        final Table table = Table.editor()
                .tableId(TableId.parse("DEBEZIUM.TEST"))
                .addColumn(Column.editor().name("ID").create())
                .addColumn(Column.editor().name("NAME").create())
                .addColumn(Column.editor().name("TS").create())
                .addColumn(Column.editor().name("UT").create())
                .addColumn(Column.editor().name("DATE").create())
                .addColumn(Column.editor().name("IS").create())
                .addColumn(Column.editor().name("IS2").create())
                .addColumn(Column.editor().name("UNUSED").create())
                .create();

        String sql = "delete from \"DEBEZIUM\".\"TEST\" " +
                "where \"ID\" = '1' and \"NAME\" = 'Acme' and \"TS\" = TO_TIMESTAMP('2020-02-01 00:00:00.') and " +
                "\"UT\" = Unsupported Type and \"DATE\" = TO_DATE('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') and " +
                "\"IS\" IS NULL and \"IS2\" IS NULL;";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.DELETE);
        assertThat(entry.getOldValues()).hasSize(8);
        assertThat(entry.getOldValues()[0]).isEqualTo("1");
        assertThat(entry.getOldValues()[1]).isEqualTo("Acme");
        assertThat(entry.getOldValues()[2]).isEqualTo("TO_TIMESTAMP('2020-02-01 00:00:00.')");
        assertThat(entry.getOldValues()[3]).isNull();
        assertThat(entry.getOldValues()[4]).isEqualTo("TO_DATE('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')");
        assertThat(entry.getOldValues()[5]).isNull();
        assertThat(entry.getOldValues()[6]).isNull();
        assertThat(entry.getOldValues()[7]).isNull();
        assertThat(entry.getNewValues()).isEmpty();
    }

    @Test
    @FixFor("DBZ-3235")
    public void testParsingUpdateWithNoWhereClauseIsAcceptable() throws Exception {
        final Table table = Table.editor()
                .tableId(TableId.parse("DEBEZIUM.TEST"))
                .addColumn(Column.editor().name("COL1").create())
                .addColumn(Column.editor().name("COL2").create())
                .addColumn(Column.editor().name("COL3").create())
                .addColumn(Column.editor().name("UNUSED").create())
                .create();

        String sql = "update \"DEBEZIUM\".\"TEST\" set \"COL1\" = '1', \"COL2\" = NULL, \"COL3\" = 'Hello';";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.UPDATE);
        assertThat(entry.getOldValues()).hasSize(4);
        assertThat(entry.getOldValues()[0]).isNull();
        assertThat(entry.getOldValues()[1]).isNull();
        assertThat(entry.getOldValues()[2]).isNull();
        assertThat(entry.getOldValues()[3]).isNull();
        assertThat(entry.getNewValues()).hasSize(4);
        assertThat(entry.getNewValues()[0]).isEqualTo("1");
        assertThat(entry.getNewValues()[1]).isNull();
        assertThat(entry.getNewValues()[2]).isEqualTo("Hello");
        assertThat(entry.getNewValues()[3]).isNull();
    }

    @Test
    @FixFor("DBZ-3235")
    public void testParsingDeleteWithNoWhereClauseIsAcceptable() throws Exception {
        final Table table = Table.editor()
                .tableId(TableId.parse("DEBEZIUM.TEST"))
                .addColumn(Column.editor().name("COL1").create())
                .addColumn(Column.editor().name("COL2").create())
                .addColumn(Column.editor().name("COL3").create())
                .addColumn(Column.editor().name("UNUSED").create())
                .create();

        String sql = "delete from \"DEBEZIUM\".\"TEST\";";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.DELETE);
        assertThat(entry.getOldValues()).hasSize(4);
        assertThat(entry.getOldValues()[0]).isNull();
        assertThat(entry.getOldValues()[1]).isNull();
        assertThat(entry.getOldValues()[2]).isNull();
        assertThat(entry.getOldValues()[3]).isNull();
        assertThat(entry.getNewValues()).isEmpty();
    }

    @Test
    @FixFor("DBZ-3258")
    public void testNameWithWhitespaces() throws Exception {
        final Table table = Table.editor()
                .tableId(new TableId(null, "UNKNOWN", "OBJ# 74858"))
                .addColumn(Column.editor().name("COL 1").create())
                .create();

        String sql = "insert into \"UNKNOWN\".\"OBJ# 74858\"(\"COL 1\") values (1)";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.INSERT);
        assertThat(entry.getOldValues()).isEmpty();
        assertThat(entry.getNewValues()).hasSize(1);
        assertThat(entry.getNewValues()[0]).isEqualTo("1");
    }

    @Test
    @FixFor("DBZ-3305")
    public void testParsingUpdateWithNoWhereClauseFunctionAsLastColumn() throws Exception {
        final Table table = Table.editor()
                .tableId(new TableId(null, "TICKETUSER", "CRS_ORDER"))
                .addColumn(Column.editor().name("AMOUNT_PAID").create())
                .addColumn(Column.editor().name("AMOUNT_UNPAID").create())
                .addColumn(Column.editor().name("PAY_STATUS").create())
                .addColumn(Column.editor().name("IS_DEL").create())
                .addColumn(Column.editor().name("TM_UPDATE").create())
                .create();
        String sql = "update \"TICKETUSER\".\"CRS_ORDER\" set \"AMOUNT_PAID\" = '0', \"AMOUNT_UNPAID\" = '540', " +
                "\"PAY_STATUS\" = '10111015', \"IS_DEL\" = '0', \"TM_UPDATE\" = TO_DATE('2021-03-17 10:18:55', 'YYYY-MM-DD HH24:MI:SS');";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.UPDATE);
        assertThat(entry.getOldValues()).hasSize(5);
        assertThat(entry.getOldValues()[0]).isNull();
        assertThat(entry.getOldValues()[1]).isNull();
        assertThat(entry.getOldValues()[2]).isNull();
        assertThat(entry.getOldValues()[3]).isNull();
        assertThat(entry.getOldValues()[4]).isNull();
        assertThat(entry.getNewValues()).hasSize(5);
        assertThat(entry.getNewValues()[0]).isEqualTo("0");
        assertThat(entry.getNewValues()[1]).isEqualTo("540");
        assertThat(entry.getNewValues()[2]).isEqualTo("10111015");
        assertThat(entry.getNewValues()[3]).isEqualTo("0");
        assertThat(entry.getNewValues()[4]).isEqualTo("TO_DATE('2021-03-17 10:18:55', 'YYYY-MM-DD HH24:MI:SS')");
    }

    @Test
    @FixFor("DBZ-3367")
    public void shouldParsingRedoSqlWithParenthesisInFunctionArgumentStrings() throws Exception {
        final Table table = Table.editor()
                .tableId(new TableId(null, "DEBEZIUM", "TEST"))
                .addColumn(Column.editor().name("C1").create())
                .addColumn(Column.editor().name("C2").create())
                .create();

        String sql = "insert into \"DEBEZIUM\".\"TEST\" (\"C1\",\"C2\") values (UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09'),NULL);";

        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.INSERT);
        assertThat(entry.getOldValues()).isEmpty();
        assertThat(entry.getNewValues()).hasSize(2);
        assertThat(entry.getNewValues()[0])
                .isEqualTo("UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09')");
        assertThat(entry.getNewValues()[1]).isNull();

        sql = "update \"DEBEZIUM\".\"TEST\" set " +
                "\"C2\" = UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09') " +
                "where \"C1\" = UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09');";
        entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.UPDATE);
        assertThat(entry.getOldValues()).hasSize(2);
        assertThat(entry.getOldValues()[0])
                .isEqualTo("UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09')");
        assertThat(entry.getOldValues()[1]).isNull();
        assertThat(entry.getNewValues()).hasSize(2);
        assertThat(entry.getNewValues()[0])
                .isEqualTo("UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09')");
        assertThat(entry.getNewValues()[1])
                .isEqualTo("UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09')");

        sql = "delete from \"DEBEZIUM\".\"TEST\" where \"C1\" = UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09');";
        entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.DELETE);
        assertThat(entry.getOldValues()).hasSize(2);
        assertThat(entry.getOldValues()[0])
                .isEqualTo("UNISTR('\\963F\\72F8\\5C0F\\706B\\8F66\\5BB6\\5EAD\\7968(\\60CA\\559C\\FF09\\FF082161\\FF09')");
        assertThat(entry.getOldValues()[1]).isNull();
        assertThat(entry.getNewValues()).isEmpty();
    }

    @Test
    @FixFor("DBZ-3413")
    public void testParsingDoubleSingleQuoteInWhereClause() throws Exception {
        final Table table = Table.editor()
                .tableId(TableId.parse("DEBEZIUM.TEST"))
                .addColumn(Column.editor().name("COL1").create())
                .addColumn(Column.editor().name("COL2").create())
                .create();

        String sql = "insert into \"DEBEZIUM\".\"TEST\"(\"COL1\",\"COL2\") values ('Bob''s dog','0');";
        LogMinerDmlEntry entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.INSERT);
        assertThat(entry.getOldValues()).isEmpty();
        assertThat(entry.getNewValues()).hasSize(2);
        assertThat(entry.getNewValues()[0]).isEqualTo("Bob''s dog");
        assertThat(entry.getNewValues()[1]).isEqualTo("0");

        sql = "update \"DEBEZIUM\".\"TEST\" set \"COL2\" = '1' where \"COL1\" = 'Bob''s dog' and \"COL2\" = '0';";
        entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.UPDATE);
        assertThat(entry.getOldValues()).hasSize(2);
        assertThat(entry.getOldValues()[0]).isEqualTo("Bob''s dog");
        assertThat(entry.getOldValues()[1]).isEqualTo("0");
        assertThat(entry.getNewValues()).hasSize(2);
        assertThat(entry.getNewValues()[0]).isEqualTo("Bob''s dog");
        assertThat(entry.getNewValues()[1]).isEqualTo("1");

        sql = "delete from \"DEBEZIUM\".\"TEST\" where \"COL1\" = 'Bob''s dog' and \"COL2\" = '1';";
        entry = fastDmlParser.parse(sql, table, null);
        assertThat(entry.getOperation()).isEqualTo(RowMapper.DELETE);
        assertThat(entry.getOldValues()).hasSize(2);
        assertThat(entry.getOldValues()[0]).isEqualTo("Bob''s dog");
        assertThat(entry.getOldValues()[1]).isEqualTo("1");
        assertThat(entry.getNewValues()).isEmpty();
    }
}
