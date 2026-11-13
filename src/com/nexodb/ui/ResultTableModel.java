package com.nexodb.ui;

import com.nexodb.db.QueryResult;

import javax.swing.table.AbstractTableModel;

public final class ResultTableModel extends AbstractTableModel {
    private QueryResult result = new QueryResult(java.util.List.of(), java.util.List.of(), -1, 0, false);

    public void setResult(QueryResult result) {
        this.result = result;
        fireTableStructureChanged();
    }

    @Override public int getRowCount() { return result.rows().size(); }
    @Override public int getColumnCount() { return result.columns().size(); }
    @Override public String getColumnName(int column) { return result.columns().get(column); }
    @Override public Object getValueAt(int rowIndex, int columnIndex) { return result.rows().get(rowIndex).get(columnIndex); }
    @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return false; }
}

