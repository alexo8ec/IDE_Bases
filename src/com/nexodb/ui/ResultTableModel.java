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
    private boolean editable;
    private Runnable changed=()->{};

    public void setResult(QueryResult result){this.result=result;rows.clear();pending.clear();deleted.clear();for(var row:result.rows())rows.add(new ArrayList<>(row));editable=false;fireTableStructureChanged();}
    public void onChange(Runnable changed){this.changed=changed;}
    public void setEditable(boolean editable){this.editable=editable;}
    public boolean isNewRow(int row){return row>=result.rows().size();}
    public boolean isDeleted(int row){return deleted.contains(row);}
    public boolean isChanged(int row,int column){return pending.containsKey(row)&&pending.get(row).containsKey(column);}
    public int changeCount(){return deleted.size()+rows.size()-result.rows().size()+pending.entrySet().stream().filter(e->!isNewRow(e.getKey())&&!deleted.contains(e.getKey())).mapToInt(e->e.getValue().size()).sum();}
    public QueryResult definition(){return result;}
    public List<QueryEditSupport.RowChange> changes(){
        List<QueryEditSupport.RowChange> changes=new ArrayList<>();
        for(int row:deleted)changes.add(new QueryEditSupport.RowChange(row,result.rows().get(row),Map.of(),DELETE));
        pending.forEach((row,values)->{if(!isNewRow(row)&&!deleted.contains(row))changes.add(new QueryEditSupport.RowChange(row,result.rows().get(row),values));});
        for(int row=result.rows().size();row<rows.size();row++)changes.add(new QueryEditSupport.RowChange(row,rows.get(row),pending.getOrDefault(row,Map.of()),INSERT));
        return changes;
    }
    public void accept(List<QueryEditSupport.RowChange> saved){
        List<List<Object>> originals=new ArrayList<>(result.rows());
        for(var row:saved){if(row.kind()!=UPDATE)throw new IllegalArgumentException("Row mutations require a refreshed result");List<Object> original=new ArrayList<>(originals.get(row.row()));for(var cell:row.values().entrySet()){rows.get(row.row()).set(cell.getKey(),cell.getValue());original.set(cell.getKey(),cell.getValue());}originals.set(row.row(),original);}
        result=new QueryResult(result.columns(),originals,result.updateCount(),result.elapsedMillis(),result.truncated(),result.editing(),result.origins());pending.clear();deleted.clear();fireTableDataChanged();changed.run();
    }
    public int insertRow(Integer duplicate){
        if(!editable||result.editing()==null||result.editing().target()==null)return -1;
        int row=rows.size();List<Object> values=new ArrayList<>(Collections.nCopies(getColumnCount(),INSTANCE));
        if(duplicate!=null){var target=result.editing().target();for(int column=0;column<getColumnCount();column++)if(target.columns().get(column).writable()&&!target.keys().contains(column))values.set(column,rows.get(duplicate).get(column));}
        rows.add(values);for(int column=0;column<getColumnCount();column++)if(values.get(column)!=INSTANCE)pending.computeIfAbsent(row,key->new LinkedHashMap<>()).put(column,values.get(column));
        fireTableRowsInserted(row,row);changed.run();return row;
    }
    public void deleteRows(List<Integer> selection){
        if(!editable)return;
        for(int row:new TreeSet<>(selection).descendingSet()){
            if(isNewRow(row)){
                rows.remove(row);Map<Integer,Map<Integer,Object>> shifted=new LinkedHashMap<>();pending.forEach((index,values)->{if(index!=row)shifted.put(index>row?index-1:index,values);});pending.clear();pending.putAll(shifted);
            }else{deleted.add(row);pending.remove(row);}
        }
        fireTableDataChanged();changed.run();
    }
    public void discard(){rows.clear();for(var row:result.rows())rows.add(new ArrayList<>(row));pending.clear();deleted.clear();fireTableDataChanged();changed.run();}
    public QueryResult snapshot(){List<List<Object>> copy=rows.stream().map(row->(List<Object>)new ArrayList<>(row)).toList();return new QueryResult(result.columns(),copy,result.updateCount(),result.elapsedMillis(),result.truncated(),result.editing(),result.origins());}
    public void setReference(int row,Map<Integer,Object> values){
        if(values.isEmpty()||values.keySet().stream().anyMatch(column->column<0||column>=getColumnCount()||!isCellEditable(row,column)))throw new IllegalStateException(tr("La referencia completa no se puede editar en este resultado."));
        values.forEach((column,value)->stage(value,row,column));fireTableRowsUpdated(row,row);changed.run();
    }
    public int getRowCount(){return rows.size();}public int getColumnCount(){return result.columns().size();}public String getColumnName(int column){return result.columns().get(column);}
    public Object getValueAt(int row,int column){return rows.get(row).get(column);}
    public boolean isCellEditable(int row,int column){return editable&&!deleted.contains(row)&&result.editing()!=null&&result.editing().target()!=null&&result.editing().target().columns().get(column).writable();}
    public void setValueAt(Object value,int row,int column){if(isCellEditable(row,column)&&!Objects.equals(value,rows.get(row).get(column))){stage(value,row,column);fireTableCellUpdated(row,column);changed.run();}}
    public void setValues(List<Integer> rowIndexes,List<Integer> columnIndexes,Object value){for(int row:rowIndexes)for(int column:columnIndexes)if(isCellEditable(row,column))stage(value,row,column);fireTableDataChanged();changed.run();}
    private void stage(Object value,int row,int column){
        rows.get(row).set(column,value);Object original=isNewRow(row)?INSTANCE:result.rows().get(row).get(column);
        if(Objects.equals(value,original)){var cells=pending.get(row);if(cells!=null){cells.remove(column);if(cells.isEmpty())pending.remove(row);}}
        else pending.computeIfAbsent(row,key->new LinkedHashMap<>()).put(column,value);
    }
}
