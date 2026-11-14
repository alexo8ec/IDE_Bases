/* Runs only inside the instrumentation APK, against the real packaged WebView UI. */
(async function () {
    function check(condition, message) { if (!condition) throw new Error(message); }
    const originalRpc = rpc;
    let response = window.NexoResultFixtures.single;
    let updateRequested = 0;
    let resumeRequested = 0,pendingUpdate=false;
    rpc = async function (method, payload, quiet) {
        if (method === 'update.check') return { available: true, version: 'test-next' };
        if (method === 'update.status') return { pending:pendingUpdate,state:pendingUpdate?'permission':'idle',installedVersion:S.version };
        if (method === 'update.download') { updateRequested++; pendingUpdate=true; return 'permission'; }
        if (method === 'update.resume') { resumeRequested++; return 'installer'; }
        if (method === 'draft.save') { check(Array.isArray(payload.draft.tabs),'Update must preserve the query tabs before installing'); return true; }
        if (method === 'references.search') return {result:{columns:['id','nombre'],rows:[['1','Cliente original'],['3','Cliente nuevo']],truncated:false},exact:true};
        if (method === 'query') {
            check(/^SELECT\b/i.test(payload.sql.trim()), 'The UI test must execute only SELECT fixtures');
            return response;
        }
        return originalRpc(method, payload, quiet);
    };
    try {
        S.connected = true;
        S.page = 'sql';
        S.catalogs = ['ui_fixture'];
        S.database = 'ui_fixture';
        S.tabs = [{ id: 'ui-result-test', title: 'SELECT test', sql: 'SELECT id, nombre, nota FROM ui_fixture;', undo: [], redo: [], changes: {}, selectedRows: [], visible: 100 }];
        S.selected = 0;
        renderSql();
        $('#editor').setSelectionRange(0, 0);
        await execute();
        check($$('#result-body tr').length === 2, 'A single SELECT must render its rows');
        check($('#result-body').textContent.includes('Móvil'), 'Unicode cell text must be displayed');
        check($('#result-body').textContent.includes('(NULL)'), 'SQL NULL must be displayed');
        check($$('[data-result-tab]').length === 0, 'One result does not require additional tabs');
        check($$('[data-cell-reference]').length===2,'FK cells must show lookup buttons');
        S.writing=false;await openRelated(0,0);check($$('[data-reference-row]').length===0,'Read-only lookup must not allow assignment');closeSheet();
        S.writing=true;await openRelated(0,0);$('[data-reference-row="1"]').click();await new Promise(resolve=>setTimeout(resolve,0));check(!!$('#confirm-no'),'Changing a reference must request confirmation');$('#confirm-no').click();await new Promise(resolve=>setTimeout(resolve,0));check(Object.keys(current().changes).length===0,'No must leave the row unchanged');
        await openRelated(0,0);$('[data-reference-row="1"]').click();await new Promise(resolve=>setTimeout(resolve,0));$('#confirm-yes').click();await new Promise(resolve=>setTimeout(resolve,0));check(current().changes[0].values[0]==='3','Selecting a parent must stage its key');check(current().result.rows[0][0]==='1','Lookup must not write or replace original result values');current().changes={};S.writing=false;

        $('#result-filter').value = 'Segundo';
        $('#result-filter').dispatchEvent(new Event('input'));
        check($$('#result-body tr').length === 1, 'Filtering must update the visible rows');
        $('#all-rows').checked = true;
        $('#all-rows').dispatchEvent(new Event('change'));
        check($('#result-body tr').classList.contains('marked'), 'Checked rows must be highlighted');

        response = window.NexoResultFixtures.empty;
        current().filter = '';
        await execute();
        check($$('#result-body tr').length === 0, 'An empty SELECT must render without errors');
        check($$('#result thead th').length === 3, 'An empty SELECT must retain its column headers');

        response = { results: [window.NexoResultFixtures.single, window.NexoResultFixtures.empty] };
        await execute();
        check($$('[data-result-tab]').length === 2, 'Multiple result tabs must render');
        $('[data-result-tab="1"]').click();
        check(current().result.resultId === 'ui-empty', 'The second result tab must be selectable');
        $('[data-result-tab="0"]').click();
        check($$('#result-body tr').length === 2, 'Returning to the first result must restore its rows');

        settings();
        check(!!$('#check-update'), 'Preferences must show a real Check for updates button');
        check($('.bottom-note').textContent.includes(S.version), 'Preferences must display the installed version');
        $('#check-update').click();
        await new Promise(resolve => setTimeout(resolve, 0));
        check(!!$('#download-update'), 'An available update must offer Download and install');
        $('#download-update').click();
        await new Promise(resolve => setTimeout(resolve, 0));
        check(updateRequested === 1, 'Download and install must call the native updater exactly once');
        await openUpdates();check(!!$('#resume-update'),'Pending APK must offer retry without redownloading');$('#resume-update').click();await new Promise(resolve=>setTimeout(resolve,0));check(resumeRequested===1&&updateRequested===1,'Retry must reuse the downloaded APK');
        closeSheet();

        window.NexoResultUiOutcome = 'OK';
    } finally {
        rpc = originalRpc;
    }
})().catch(error => { window.NexoResultUiOutcome = 'FAILED: ' + error.stack; });
