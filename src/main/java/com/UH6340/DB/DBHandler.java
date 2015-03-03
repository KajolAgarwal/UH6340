package com.UH6340.DB;

import com.UH6340.SQL.SQLCommandHandler;
import com.UH6340.SQL.SQLParser;
import org.antlr.v4.runtime.misc.Pair;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Used to provide an easy interface with the database, accepts SQL strings to handle actions
 */

public class DBHandler {
    DataDictionary dictionary;
    SQLCommandHandler sql;

    public DBHandler() throws Exception {
        try {
            dictionary = new DataDictionary();
            sql = new SQLCommandHandler();
        } catch (IOException ex) {
            throw new IOException(ex.getMessage());
        }
    }

    public void handleCommand(String sqlCommand) {
        try {
            // Parses the command
            sql.handleMessage(sqlCommand);

            switch (sql.getQueryType()) {
                case SQLParser.RULE_tableCreation:
                    createTable(sql.getTables().get(0), sql.getFields());
                    break;
                case SQLParser.RULE_tableInsertion:
                    insertIntoTable(sql.getTables().get(0), sql.getValues());
                    break;
                case SQLParser.RULE_tableSelection:
                    selectFromTable(sql.getTables(), sql.getSelections(), sql.getQueries());
                    break;
                default:
                    break;
            }
        } catch (Exception ex) {
            System.err.println("Unable to process SQL command: " + mysqlCommand + "\n" + ex.getMessage() + "\n");
        }
    }

    private void selectFromTable(ArrayList<String> tables, ArrayList<String> selections, ArrayList<Pair<String, String>> queries) throws Exception {
        // Make sure all tables exist within the query
        for (String table : tables) {
            if (!dictionary.checkIfTableExists(table)) {
                throw new IOException("Table " + table + " does not exist");
            }
        }

        LinkedList<LinkedList<String>> tuples = new LinkedList<LinkedList<String>>();

        // Projection operation
        if (tables.size==1) {
            File tableHandle = dictionary.getTableFileHandle(tables.get(0));

            // Find the indices that matter for this table and the projection
            LinkedList<Integer> indices = new LinkedList<Integer>();
            if (selections.get(0).equals("*")) {
                for (int i=0; i < dictionary.getTable(tables.get(0)).fields.size(); i++) {
                    indices.add(i);
                }
            } else {
                for (String selection : selections) {
                    indices.add(dictionary.getIndexOfField(tables.get(0), selection));
                }
            }

            LinkedList<Pair<Integer, String>> constraints = new LinkedList<Pair<Integer, String>>();
            // Determine the queries on what indices
            if (queries.size() > 0) {
                for (Pair<String, String> query : queries) {
                    constraints.add(new Pair<Integer, String>(dictionary.getIndexOfField(tables.get(0), query.a), query.b.replace("\"", "")));
                }
            }

            BufferedReader reader = new BufferedReader(new FileReader(tableHandle));
            String buffer;

            // Iterate through the file
            while ((buffer = reader.readLine()) != null && buffer.length() != 0) {
                String[] values = buffer.split(","); 

                // Grab the correct values from the indices
                LinkedList<String> tuple = new LinkedList<String>();
                if (constraints.size() == 0) {
                    for (Integer index : indices) {
                        tuple.add(values[index]);
                    }
                } else {
                    for (Pair<Integer, String> constraint : constraints) {
                        if (values[constraint.a].equals(constraint.b)) {
                            for (Integer index : indices) {
                                tuple.add(values[index]);
                            }
                        }
                    }
                }

                if (tuple.size() > 0) {
                    tuples.add(tuple);
                }
            }
            reader.close();
        } else {
            // Join operation
            String table1 = tables.get(0);
            String table2 = tables.get(1);
            Integer table1JoinIndex = dictionary.getIndexOfField(table1, queries.get(0).a);
            Integer table2JoinIndex = dictionary.getIndexOfField(table2, queries.get(0).b);
            ArrayList<Integer> table2Indices = new ArrayList<Integer>();

            // Figure out which fields from table2 to use
            DataDictionary.Table table1Info = dictionary.getTable(table1);
            DataDictionary.Table table2Info = dictionary.getTable(table2);

            for (String field : table2Info.fields.keySet()) {
                if (!table1Info.fields.containsKey(field)) {
                    table2Indices.add(dictionary.getIndexOfField(table2, field));
                }
            }

            BufferedReader table1Reader = new BufferedReader(new FileReader(dictionary.getTableFileHandle(table1)));
            String table1Buffer, table2Buffer;

            while ((table1Buffer = table1Reader.readLine()) != null && table1Buffer.length() != 0) {
                String[] table1Values = table1Buffer.split(",");

                BufferedReader table2Reader = new BufferedReader(new FileReader(dictionary.getTableFileHandle(table2)));
                while ((table2Buffer = table2Reader.readLine()) != null && table2Buffer.length() != 0) {
                    String[] table2Values = table2Buffer.split(",");

                    if (table1Values[table1JoinIndex].equals(table2Values[table2JoinIndex])) {
                        LinkedList<String> tuple = new LinkedList<String>();

                        tuple.addAll(Arrays.asList(table1Values));
                        for (int i = 0; i < table2Values.length; i++) {
                            if (table2Indices.contains(i) && i != table2JoinIndex) {
                                tuple.add(table2Values[i]);
                            }
                        }

                        if (tuple.size() > 0) {
                            tuples.add(tuple);
                        }
                    }
                }
                table2Reader.close();
            }
            table1Reader.close();
        }

        // Print out data in terminal
        if (tuples.size() > 0) {
            for (LinkedList<String> tuple : tuples) {
                StringBuilder buffer = new StringBuilder();
                for (int i = 0; i < tuple.size(); i++) {
                    buffer.append(tuple.get(i));
                    if (i != tuple.size() - 1) {
                        buffer.append(",");
                    }
                }
                System.out.println(buffer.toString());
            }
        }
    }

    private void insertIntoTable(String tableName, ArrayList<String> values) throws Exception {
        // Check to see if the table exists
        if (dictionary.checkIfTableExists(tableName)) {
            DataDictionary.Table table = dictionary.getTable(tableName);

            // Check to see if this is a valid insertion
            if (values.size() == table.fields.size() && checkDatatypes(values, table)) {
                // Craft the new tuple && append to the file
                addToTable(tableName, craftTuple(values));
            } else {
                StringBuilder schema = new StringBuilder();
                schema.append("(");
                for (int i = 0; i < table.fieldOrder.size(); i++) {
                    schema.append(table.fieldOrder.get(i));
                    schema.append(":");
                    schema.append(table.fields.get(table.fieldOrder.get(i)));
                    if (i != table.fieldOrder.size() - 1) {
                        schema.append(", ");
                    }
                }
                schema.append(")");
                throw new IOException("Insertion operation on " + tableName + " failed due to schema mismatch" + "\nExpected schema:" + schema.toString());
            }
        } else {
            throw new IOException("Table " + tableName + " doesn't exist in the database");
        }
    }

    private void addToTable(String tableName, String tuple) throws IOException {
        try {
            BufferedWriter tableFile = new BufferedWriter(new FileWriter(dictionary.getTableFileHandle(tableName), true));
            tableFile.append(tuple);
            tableFile.close();
        } catch (IOException ex) {
            throw new IOException("Failed to write insertion to file");
        }
    }

    private String craftTuple(ArrayList<String> values) {
        StringBuilder buffer = new StringBuilder();
        for (String value : values) {
            buffer.append(value);
            buffer.append(",");
        }
        buffer.append("\n");
        return buffer.toString();
    }

    private boolean checkDatatypes(ArrayList<String> values, DataDictionary.Table table) {
        for (int i = 0; i < values.size(); i++) {
            // Check to see if it's an integer
            if (!values.get(i).matches("^\\d+") && table.fields.get(table.fieldOrder.get(i)).equals("INT")) {
                // Invalid INT
                return false;
            }
        }

        // Else it's a STRING and thus can be any series of characters
        return true;
    }

    private void createTable(String tableName, ArrayList<Pair<String, String>> fields) throws IOException {
        dictionary.addTable(tableName, fields);
    }
}
