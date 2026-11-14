#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
bash "$root/build.sh"
test_classes="$root/build/test-classes"
[[ "$test_classes" == "$root/build/test-classes" ]] || exit 1
rm -rf -- "$test_classes"
mkdir -p "$test_classes"
sources=()
while IFS= read -r -d '' source; do sources+=("$source"); done < <(find "$root/tests" -name '*.java' -print0)
sources+=("$root/license-issuer/src/com/nexodb/issuer/IssuerAuthority.java" "$root/license-issuer/src/com/nexodb/issuer/IssuerVault.java")
classpath="$root/build/classes:$root/build/lib/*"
javac --release 17 -encoding UTF-8 -cp "$classpath" -d "$test_classes" "${sources[@]}"
for test in util.SqlTextTest CoreTest db.JdbcSessionTest debug.MysqlProcedureTest db.JdbcMultipleResultsTest ai.AiClientTest reporting.ReportingTest reporting.ReportDesignerTest db.ManagedConnectionTest db.UserAdministrationTest db.ForeignKeyLookupTest ui.AccountConnectionUiTest db.PostgresSupportTest \
  ui.CompletionInteractionTest db.ZeroDateTest util.SecretStoreTest util.DataExporterTest \
  db.DriverDownloadServiceTest update.UpdateServiceTest ui.AppearanceTest util.SecureDownloadTest ui.LanguageSwitchTest \
  db.SchemaMetadataReaderTest ui.ComparisonSelectionTest ui.ConnectionFeedbackTest db.QueryEditSqlTest db.SqlColumnCompletionTest ui.SqlDraftPersistenceTest ui.QueryTabsTest db.SqlScriptReaderTest ui.SqlDocumentTest licensing.LicenseTest; do
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" "com.nexodb.$test"
done
if [[ -f "$root/build/sqlite-test.jar" ]]; then
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.db.SQLiteConnectionTest
fi
if [[ -f "$root/build/h2-test.jar" ]]; then
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.db.QueryPagingTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.ui.QueryPagingUiTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.db.QueryPlanTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.ui.QueryBatchTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.db.QueryEditIntegrationTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.db.VisualToolsTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.db.CsvJobsIntegrationTest
  java -Dnexodb.disableDraftPersistence=true -Djava.awt.headless=true -cp "$test_classes:$classpath" com.nexodb.ui.QuerySafetyTest
fi
