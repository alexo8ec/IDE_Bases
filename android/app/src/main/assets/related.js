'use strict';
let relatedSequence=0;
function relatedReferences(result,column){return (result.references||[]).map((ref,index)=>({ref,index})).filter(item=>item.ref.parts.some(part=>part.column===column));}
async function openRelated(row,column,referenceIndex){
    const instance=++relatedSequence;
    const q=current(),r=q.result,matches=relatedReferences(r,column);if(!matches.length)return;
    if(referenceIndex===undefined&&matches.length>1){sheet('Ver referencia',matches.map(item=>'<button class="list-item" data-reference="'+item.index+'">'+h(item.ref.parentTable)+' · '+h(item.ref.name||'')+'</button>').join(''));$$('[data-reference]').forEach(el=>on(el,'click',()=>openRelated(row,column,Number(el.dataset.reference))));return;}
    const index=referenceIndex===undefined?matches[0].index:referenceIndex,ref=r.references[index],change=q.changes[row],values=r.rows[row].slice();
    if(change&&change.values)Object.keys(change.values).forEach(key=>values[Number(key)]=change.values[key]);
    const writable=!!(S.writing&&r.editing&&r.editing.target&&(!change||change.kind!=='DELETE')&&ref.parts.every(part=>part.column>=0&&r.editing.target.columns[part.column].writable));
    let first=0,filter=values[column]===null||typeof values[column]==='object'?'':String(values[column]),field='',last=null,request=0;
    sheet('Registro relacionado','<h3>'+h(ref.childTable)+' → '+h(ref.parentTable)+'</h3><div class="notice">'+h(ref.parts.map(part=>part.local+' → '+part.referenced).join(', '))+'<br>'+h(t(writable?'La referencia se aplicará al pulsar Guardar.':'Solo lectura'))+'</div><div class="row">'+fieldInput()+'</div><div class="toolbar">'+button('reference-find','Buscar','search')+check('reference-limit','Limitar filas',true)+'<input id="reference-size" type="number" min="1" max="1000" value="25" aria-label="'+h(t('Máx. filas'))+'" style="width:85px"></div>'+ (ref.parts.length>1?check('reference-others','Filtrar por las otras claves de la fila',true):'')+'<div id="reference-status" role="status"></div><div id="reference-data" class="table-scroll"></div><div class="toolbar">'+button('reference-previous','Anterior','chevron')+'<span id="reference-offset"></span>'+button('reference-next','Siguiente','chevron')+'</div>');
    function fieldInput(){return '<input id="reference-search" value="'+h(filter)+'" placeholder="'+h(t('Buscar en la tabla relacionada'))+'"><select id="reference-column"><option value="">'+h(t('Todas las columnas'))+'</option></select>';}
    async function load(){
        const token=++request;filter=$('#reference-search').value;field=$('#reference-column').value;const unlimited=!$('#reference-limit').checked,limit=Number($('#reference-size').value);
        if(!Number.isInteger(limit)||limit<1||limit>1000){toast(t('Límite o posición no válidos.'));return;}
        $('#reference-find').disabled=true;$('#reference-status').textContent=t('Leyendo tabla referenciada…');
        try{
            const page=await rpc('references.search',{resultId:r.resultId,reference:index,column,values,text:filter,searchColumn:field,matchOthers:!$('#reference-others')||$('#reference-others').checked,offset:unlimited?0:first,limit,unlimited});
            if(instance!==relatedSequence||token!==request||!$('#reference-data'))return;last=page.result;
            $('#reference-column').innerHTML='<option value="">'+h(t('Todas las columnas'))+'</option>'+last.columns.map(name=>'<option value="'+h(name)+'">'+h(name)+'</option>').join('');$('#reference-column').value=field;
            $('#reference-data').innerHTML='<table><thead><tr>'+(writable?'<th></th>':'')+last.columns.map(name=>'<th>'+h(name)+'</th>').join('')+'</tr></thead><tbody>'+last.rows.map((parent,i)=>'<tr>'+(writable?'<td><button class="reference-select" data-reference-row="'+i+'" aria-label="'+h(t('Seleccionar referencia'))+'">✓</button></td>':'')+parent.map(value=>'<td>'+h(value===null?'(NULL)':value)+'</td>').join('')+'</tr>').join('')+'</tbody></table>';
            $('#reference-status').textContent=last.rows.length+' '+t('filas')+' · '+t(page.exact?'Coincidencia exacta':'Resultados de búsqueda');$('#reference-offset').textContent=String(unlimited?0:first);$('#reference-previous').disabled=unlimited||first===0;$('#reference-next').disabled=unlimited||!last.truncated;
            $$('[data-reference-row]').forEach(el=>on(el,'click',async()=>{
                if(!writable||current()!==q||q.result!==r||!canEdit())return;
                const parent=last.rows[Number(el.dataset.referenceRow)],chosen={};for(const part of ref.parts){const at=last.columns.indexOf(part.referenced);if(at<0||parent[at]===null)throw new Error(t('La columna referenciada ya no existe.'));chosen[part.column]=parent[at];}
                if(!await confirmLocal('Cambiar referencia','Se actualizarán juntas las columnas de esta relación en el borrador. Pulsa Guardar para aplicar a la base. ¿Continuar?'))return;const staged=q.changes[row]||{row,kind:'UPDATE',values:{}};Object.assign(staged.values,chosen);q.changes[row]=staged;closeSheet();renderResult();
            }));
        }catch(error){if(instance===relatedSequence&&token===request&&$('#reference-data')){last=null;$('#reference-data').innerHTML='';$('#reference-status').textContent=t('No se pudo consultar la referencia.');}throw error;
        }finally{if(instance===relatedSequence&&token===request&&$('#reference-find'))$('#reference-find').disabled=false;}
    }
    on('#reference-find','click',()=>{first=0;return load();});on('#reference-search','keydown',e=>{if(e.key==='Enter'){first=0;return load();}});on('#reference-limit','change',()=>{first=0;return load();});on('#reference-column','change',()=>{first=0;return load();});on('#reference-others','change',()=>{first=0;return load();});
    on('#reference-previous','click',()=>{first=Math.max(0,first-Number($('#reference-size').value));return load();});on('#reference-next','click',()=>{first+=Number($('#reference-size').value);return load();});await load();
}
