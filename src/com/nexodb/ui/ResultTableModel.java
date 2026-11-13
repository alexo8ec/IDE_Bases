package com.nexodb.ui;

import com.nexodb.db.QueryResult;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.nexodb.db.QueryEditSupport;

public final class ResultTableModel extends AbstractTableModel {
    private QueryResult result = new QueryResult(java.util.List.of(), java.util.List.of(), -1, 0, false);
    private final List<List<Object>> rows = new ArrayList<>();
    private final Map<Integer,Map<Integer,Object>> pending = new LinkedHashMap<>();
    private boolean editable;
    private Runnable changed = () -> {};

    public void setResult(QueryResult result) {
        this.result = result;
        rows.clear();
        pending.clear();
        for (List<Object> row : result.rows()) rows.add(new ArrayList<>(row));
        editable = false;
        fireTableStructureChanged();
    }

    public void onChange(Runnable changed) { this.changed = changed; }
    public void setEditable(boolean editable) { this.editable = editable; }
    public void saved(int row, int column, Object value) { rows.get(row).set(column,value); fireTableCellUpdated(row,column); }
    public List<QueryEditSupport.RowChange> changes() {
        List<QueryEditSupport.RowChange> changes=new ArrayList<>();
        pending.forEach((row,values)->changes.add(new QueryEditSupport.RowChange(row,result.rows().get(row),values)));
        return changes;
    }
    public boolean isChanged(int row,int column){return pending.containsKey(row)&&pending.get(row).containsKey(column);}
    public int changeCount(){return pending.values().stream().mapToInt(Map::size).sum();}
    public QueryResult definition(){return result;}
    public void accept(List<QueryEditSupport.RowChange> saved){
        List<List<Object>> originals=new ArrayList<>(result.rows());
        for(var row:saved){List<Object> original=new ArrayList<>(originals.get(row.row()));for(var cell:row.values().entrySet()){rows.get(row.row()).set(cell.getKey(),cell.getValue());original.set(cell.getKey(),cell.getValue());}originals.set(row.row(),original);}
        result=new QueryResult(result.columns(),originals,result.updateCount(),result.elapsedMillis(),result.truncated(),result.editing());
        pending.clear();fireTableDataChanged();changed.run();
    }
    public void discard(){for(int row:pending.keySet())rows.set(row,new ArrayList<>(result.rows().get(row)));pending.clear();fireTableDataChanged();changed.run();}
    public QueryResult snapshot() {
        List<List<Object>> copy = rows.stream().map(row -> (List<Object>) new ArrayList<>(row)).toList();
        return new QueryResult(result.columns(), copy, result.updateCount(), result.elapsedMillis(), result.truncated(), result.editing());
    }
    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return result.columns().size(); }
    @Override public String getColumnName(int column) { return result.columns().get(column); }
    @Override public Object getValueAt(int rowIndex, int columnIndex) { return rows.get(rowIndex).get(columnIndex); }
    @Override public boolean isCellEditable(int rowIndex, int columnIndex) {
        return editable && result.editing() != null && result.editing().target() != null
                && result.editing().target().columns().get(columnIndex).writable();
    }
    @Override public void setValueAt(Object value, int row, int column) {
        if (isCellEditable(row,column) && !java.util.Objects.equals(value,rows.get(row).get(column))) {
            stage(value,row,column);
            fireTableCellUpdated(row,column);changed.run();
        }
    }
    public void setValues(List<Integer> rowIndexes,List<Integer> columnIndexes,Object value){
        for(int row:rowIndexes)for(int column:columnIndexes)if(isCellEditable(row,column))stage(value,row,column);
        fireTableDataChanged();changed.run();
    }
    private void stage(Object value,int row,int column){
        rows.get(row).set(column,value);
        if(java.util.Objects.equals(value,result.rows().get(row).get(column))){Map<Integer,Object> cells=pending.get(row);if(cells!=null){cells.remove(column);if(cells.isEmpty())pending.remove(row);}}
        else pending.computeIfAbsent(row,ignored->new LinkedHashMap<>()).put(column,value);
    }
}

