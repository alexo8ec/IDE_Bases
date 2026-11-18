package com.nexodb.ui;

import com.nexodb.db.QueryResult;
import com.nexodb.db.QueryEditSupport;
import javax.swing.table.AbstractTableModel;
import java.util.*;
import static com.nexodb.db.QueryEditSupport.ChangeKind.*;
import static com.nexodb.db.QueryEditSupport.DefaultValue.INSTANCE;
import static com.nexodb.util.I18n.tr;

public final class ResultTableModel extends AbstractTableModel {
    private QueryResult result=new QueryResult(List.of(),List.of(),-1,0,false);
    private final List<List<Object>> rows=new ArrayList<>();
    private final Map<Integer,Map<Integer,Object>> pending=new LinkedHashMap<>();
    private final Set<Integer> deleted=new TreeSet<>();
    private final Set<Integer> appendRows=new TreeSet<>();
    private QueryEditSupport.Target target;
    private boolean editable;
    private Runnable changed=()->{};

    public void setResult(QueryResult result){this.result=result;target=result.editing()==null?null:result.editing().target();rows.clear();pending.clear();deleted.clear();appendRows.clear();for(var row:result.rows())rows.add(new ArrayList<>(row));editable=false;fireTableStructureChanged();}
    public void setTarget(QueryEditSupport.Target target){this.target=target;if(editable)ensureAppendRow();fireTableDataChanged();changed.run();}
    public QueryEditSupport.Target target(){return target;}
    public void onChange(Runnable changed){this.changed=changed;}
    public void setEditable(boolean editable){this.editable=editable;if(editable)ensureAppendRow();else removeAppendRows();}
    public boolean isNewRow(int row){return row>=result.rows().size();}
    public boolean isAppendRow(int row){return appendRows.contains(row);}
    public int dataRowCount(){return rows.size()-appendRows.size();}
    public boolean isDeleted(int row){return deleted.contains(row);}
    public boolean isChanged(int row,int column){return pending.containsKey(row)&&pending.get(row).containsKey(column);}
    public int changeCount(){return deleted.size()+(int)java.util.stream.IntStream.range(result.rows().size(),rows.size()).filter(row->!appendRows.contains(row)).count()+pending.entrySet().stream().filter(e->!isNewRow(e.getKey())&&!deleted.contains(e.getKey())).mapToInt(e->e.getValue().size()).sum();}
    public QueryResult definition(){return result;}
    public List<QueryEditSupport.RowChange> changes(){
        List<QueryEditSupport.RowChange> changes=new ArrayList<>();
        for(int row:deleted)changes.add(new QueryEditSupport.RowChange(row,result.rows().get(row),Map.of(),DELETE));
        pending.forEach((row,values)->{if(!isNewRow(row)&&!deleted.contains(row))changes.add(new QueryEditSupport.RowChange(row,result.rows().get(row),values));});
        for(int row=result.rows().size();row<rows.size();row++)if(!appendRows.contains(row))changes.add(new QueryEditSupport.RowChange(row,rows.get(row),pending.getOrDefault(row,Map.of()),INSERT));
        return changes;
    }
    public List<QueryEditSupport.RowChange> changesForRow(int row){return changes().stream().filter(change->change.row()==row&&change.kind()==UPDATE).toList();}
    public void accept(List<QueryEditSupport.RowChange> saved){
        List<List<Object>> originals=new ArrayList<>(result.rows());
        Set<Integer> updated=new TreeSet<>();
        for(var row:saved){if(row.kind()!=UPDATE)throw new IllegalArgumentException("Row mutations require a refreshed result");List<Object> original=new ArrayList<>(originals.get(row.row()));for(var cell:row.values().entrySet()){rows.get(row.row()).set(cell.getKey(),cell.getValue());original.set(cell.getKey(),cell.getValue());}originals.set(row.row(),original);pending.remove(row.row());deleted.remove(row.row());updated.add(row.row());}
        result=new QueryResult(result.columns(),originals,result.updateCount(),result.elapsedMillis(),result.truncated(),result.editing(),result.origins(),result.jdbcTypes(),result.hiddenColumns(),result.timing());updated.forEach(row->fireTableRowsUpdated(row,row));changed.run();
    }
    public int insertRow(Integer duplicate){
        if(!editable||target==null)return -1;
        removeAppendRows();
        int row=rows.size();List<Object> values=new ArrayList<>(Collections.nCopies(target.columns().size(),INSTANCE));
        if(duplicate!=null)for(int column=0;column<getColumnCount();column++)if(target.columns().get(column).writable()&&!target.keys().contains(column))values.set(column,rows.get(duplicate).get(column));
        rows.add(values);for(int column=0;column<values.size();column++)if(values.get(column)!=INSTANCE)pending.computeIfAbsent(row,key->new LinkedHashMap<>()).put(column,values.get(column));
        fireTableRowsInserted(row,row);ensureAppendRow();changed.run();return row;
    }
    public void deleteRows(List<Integer> selection){
        if(!editable)return;
        for(int row:new TreeSet<>(selection).descendingSet()){
            if(isNewRow(row)){
                rows.remove(row);Map<Integer,Map<Integer,Object>> shifted=new LinkedHashMap<>();pending.forEach((index,values)->{if(index!=row)shifted.put(index>row?index-1:index,values);});pending.clear();pending.putAll(shifted);shiftAfterRemoval(appendRows,row);
            }else{deleted.add(row);pending.remove(row);}
        }
        appendPlaceholder();fireTableDataChanged();changed.run();
    }
    public void discard(){rows.clear();for(var row:result.rows())rows.add(new ArrayList<>(row));pending.clear();deleted.clear();appendRows.clear();appendPlaceholder();fireTableDataChanged();changed.run();}
    public QueryResult snapshot(){int visible=getColumnCount();List<List<Object>> copy=new ArrayList<>();for(int row=0;row<rows.size();row++)if(!appendRows.contains(row))copy.add(Collections.unmodifiableList(new ArrayList<>(rows.get(row).subList(0,visible))));List<QueryResult.Origin> origins=result.origins().size()>=visible?result.origins().subList(0,visible):List.of();return new QueryResult(result.columns().subList(0,visible),List.copyOf(copy),result.updateCount(),result.elapsedMillis(),result.truncated(),null,origins,result.jdbcTypes().subList(0,visible),0,result.timing());}
    public int jdbcType(int column){return column>=0&&column<result.jdbcTypes().size()?result.jdbcTypes().get(column):java.sql.Types.OTHER;}
    public void setReference(int row,Map<Integer,Object> values){
        if(values.isEmpty()||values.keySet().stream().anyMatch(column->column<0||column>=getColumnCount()||!isCellEditable(row,column)))throw new IllegalStateException(tr("La referencia completa no se puede editar en este resultado."));
        values.forEach((column,value)->stage(value,row,column));fireTableRowsUpdated(row,row);changed.run();
    }
    public int getRowCount(){return rows.size();}public int getColumnCount(){return result.visibleColumnCount();}public String getColumnName(int column){return result.columns().get(column);}
    public Object getValueAt(int row,int column){return rows.get(row).get(column);}
    public boolean isCellEditable(int row,int column){return editable&&!deleted.contains(row)&&target!=null&&target.columns().get(column).writable()&&!(isNewRow(row)&&target.columns().get(column).autoIncrement());}
    public void setValueAt(Object value,int row,int column){if(isCellEditable(row,column)&&!Objects.equals(value,rows.get(row).get(column))){stage(value,row,column);fireTableCellUpdated(row,column);changed.run();}}
    public void setValues(List<Integer> rowIndexes,List<Integer> columnIndexes,Object value){for(int row:rowIndexes)for(int column:columnIndexes)if(isCellEditable(row,column))stage(value,row,column);fireTableDataChanged();changed.run();}
    private void stage(Object value,int row,int column){
        boolean append=appendRows.remove(row);
        rows.get(row).set(column,value);Object original=isNewRow(row)?INSTANCE:result.rows().get(row).get(column);
        if(Objects.equals(value,original)){var cells=pending.get(row);if(cells!=null){cells.remove(column);if(cells.isEmpty())pending.remove(row);}}
        else pending.computeIfAbsent(row,key->new LinkedHashMap<>()).put(column,value);
        if(append)ensureAppendRow();
    }
    private void ensureAppendRow(){int before=rows.size();appendPlaceholder();if(rows.size()!=before)fireTableRowsInserted(before,before);}
    private void appendPlaceholder(){if(!editable||target==null||!appendRows.isEmpty())return;int row=rows.size();rows.add(new ArrayList<>(Collections.nCopies(target.columns().size(),INSTANCE)));appendRows.add(row);}
    private void removeAppendRows(){boolean removed=false;for(int row:new TreeSet<>(appendRows).descendingSet()){if(row>=0&&row<rows.size()){rows.remove(row);removed=true;}pending.remove(row);}appendRows.clear();if(removed)fireTableDataChanged();}
    private static void shiftAfterRemoval(Set<Integer> indexes,int removed){Set<Integer> shifted=new TreeSet<>();for(int index:indexes)if(index!=removed)shifted.add(index>removed?index-1:index);indexes.clear();indexes.addAll(shifted);}
}
