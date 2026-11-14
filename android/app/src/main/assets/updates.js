'use strict';
let updateSequence=0;
async function openUpdates(){
    const instance=++updateSequence;
    sheet('Actualizaciones','<p>'+h(t('Versión instalada'))+': <strong>'+h(S.version||'')+'</strong></p><p id="update-checking" role="status">'+h(t('Buscando actualizaciones…'))+'</p>');
    try{
        const [info,state]=await Promise.all([rpc('update.check'),rpc('update.status',{},true)]);
        if(instance!==updateSequence||!$('#update-checking'))return;
        const pending=!!state.pending,available=!!info.available;
        $('#updates').classList.toggle('update-available',pending||available);
        const html='<p>'+h(t('Versión instalada'))+': <strong>'+h(S.version||'')+'</strong></p><div class="notice">'+h(t(available?'Nueva versión disponible':'Ya tienes la versión más reciente.'))+(available?' <strong>'+h(info.version)+'</strong>':'')+'</div>'+(info.notes?'<p>'+h(info.notes)+'</p>':'')+(pending?'<p>'+h(t('La APK ya está descargada. Puedes reintentar la instalación.'))+'</p>':'')+'<p class="muted">'+h(t('La actualización conserva tus conexiones, consultas y licencia. Android pedirá confirmar la instalación.'))+'</p><div id="update-status" role="status"></div>';
        const actions=[];if(pending)actions.push({id:'resume-update',label:'Reintentar instalación',icon:'refresh',primary:true,run:()=>install('update.resume')});
        if(available)actions.push({id:'download-update',label:'Descargar e instalar',icon:'download',primary:!pending,run:()=>install('update.download')});actions.push({id:'refresh-update',label:'Buscar de nuevo',icon:'refresh',run:openUpdates});
        sheet('Actualizaciones',html,actions);
        async function install(method){
            if(S.tabs.some(q=>Object.keys(q.changes||{}).length)){toast(t('Guarda o descarta los cambios antes de actualizar.'));return;}
            clearTimeout(saveTimer);await rpc('draft.save',{draft:{tabs:S.tabs.map(q=>({id:q.id,title:q.title,sql:q.sql})),selected:S.selected,graphs:S.graphs}},true);
            const status=await rpc(method);if($('#update-status'))$('#update-status').textContent=t(status==='permission'?'Autoriza instalar desde NexoDB y vuelve a la aplicación.':'Confirma la actualización en el instalador de Android.');
            toast(t(status==='permission'?'Autoriza instalar desde NexoDB y vuelve a la aplicación.':'Confirma la actualización en el instalador de Android.'));
        }
    }catch(error){if(instance===updateSequence&&$('#update-checking'))sheet('Actualizaciones','<p class="error">'+h(error.message||String(error))+'</p>',[{id:'retry-update',label:'Reintentar',icon:'refresh',run:openUpdates}]);}
}
