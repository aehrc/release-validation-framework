package org.ihtsdo.rvf.core.service.duck;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckStoreTest {

	private static final String STORE = """
			{
			 "formatVersion": 1,
			 "generator": {"tool": "publish_store.py", "corpus": "test"},
			 "sentinels": [
			  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
			  {"placeholder": "<MODULEID>", "sentinel": "rvfph_moduleid_"},
			  {"placeholder": "<MODULEIDS>", "sentinel": "rvfph_moduleids_"}
			 ],
			 "tableColumns": {"concept_s": "id BIGINT, active VARCHAR"},
			 "ports": ["CREATE OR REPLACE MACRO m(x) AS x"],
			 "prerequisites": [
			  {"file": "pre-requisites.sql", "statements": ["create table a as select 1", "create table b as select 2"]}
			 ],
			 "assertions": {
			  "aaa-1": {"file": "one.sql", "text": "Assertion one", "keywords": "component-centric",
			            "severity": "WARNING", "statements": ["select 1", "select 2"]},
			  "bbb-2": {"file": "two.sql", "statements": ["select 3"]}
			 }
			}
			""";

	@Test
	void readsAssertionsWithTheirMetadata() throws IOException {
		DuckStore store = DuckStore.parse(STORE);
		DuckStore.StoredAssertion a = store.assertions().get("aaa-1");
		assertEquals("one.sql", a.file());
		assertEquals("Assertion one", a.text());
		assertEquals("component-centric", a.keywords());
		assertEquals("WARNING", a.severity());
		assertEquals(2, a.statements().size());
	}

	@Test
	void absentMetadataReadsAsEmptyNotNull() throws IOException {
		// text and keywords are what assertion-group rules match on, and a null
		// there is an NPE deep inside group resolution rather than a miss.
		DuckStore.StoredAssertion b = DuckStore.parse(STORE).assertions().get("bbb-2");
		assertEquals("", b.text());
		assertEquals("", b.keywords());
		assertEquals("", b.severity());
	}

	@Test
	void sentinelOrderIsPreserved() throws IOException {
		// <MODULEID>'s sentinel is a prefix of <MODULEIDS>'s but for the trailing
		// terminator, so the binder must see them in the order the publisher
		// applied them.
		assertEquals("[<RUNID>, <MODULEID>, <MODULEIDS>]",
				DuckStore.parse(STORE).sentinels().keySet().toString());
	}

	@Test
	void prerequisiteStatementsAreFlattenedInOrder() throws IOException {
		assertEquals("[create table a as select 1, create table b as select 2]",
				DuckStore.parse(STORE).prerequisiteStatements().toString());
	}

	@Test
	void portsAndTableColumnsAreRead() throws IOException {
		DuckStore store = DuckStore.parse(STORE);
		assertEquals(1, store.ports().size());
		assertEquals("id BIGINT, active VARCHAR", store.tableColumns().get("concept_s"));
	}

	@Test
	void generatorProvenanceIsCarried() throws IOException {
		assertTrue(DuckStore.parse(STORE).generatorDescription().contains("publish_store.py"));
	}

	@Test
	void anUnsupportedFormatVersionIsRefused() {
		// Every section is looked up by name with a silent default, so a store
		// shaped for another runtime would otherwise read as empty - and an empty
		// corpus reports zero findings and PASSES.
		IOException e = assertThrows(IOException.class,
				() -> DuckStore.parse(STORE.replace("\"formatVersion\": 1", "\"formatVersion\": 2")));
		assertTrue(e.getMessage().contains("formatVersion 2"));
	}

	@Test
	void aStoreWithNoVersionAtAllIsRefused() {
		assertThrows(IOException.class, () -> DuckStore.parse("{\"assertions\": {}}"));
	}

	@Test
	void aColumnListCarryingSqlIsRefused() {
		// tableColumns reaches DDL and read_csv before a run's connection is locked down.
		IOException e = assertThrows(IOException.class, () -> DuckStore.parse(STORE.replace(
				"\"id BIGINT, active VARCHAR\"",
				"\"id BIGINT); COPY (SELECT 1) TO '/tmp/x.csv'; CREATE TABLE z (a INT\"")));
		assertTrue(e.getMessage().contains("tableColumns declares concept_s"), e.getMessage());
	}

	@Test
	void aTableNameThatIsNotAPlainIdentifierIsRefused() {
		IOException e = assertThrows(IOException.class, () -> DuckStore.parse(STORE.replace(
				"\"concept_s\": \"id", "\"concept_s (a INT); COPY x TO 'y'; CREATE TABLE t\": \"id")));
		assertTrue(e.getMessage().contains("not a plain identifier"), e.getMessage());
	}

	@Test
	void anUnknownColumnTypeIsRefusedAndASizedOneAccepted() throws IOException {
		assertThrows(IOException.class, () -> DuckStore.parse(STORE.replace("active VARCHAR", "active BLOB")));
		assertThrows(IOException.class, () -> DuckStore.parse(STORE.replace("active VARCHAR", "active DECIMAL(10,2)")));
		assertEquals("id BIGINT, active VARCHAR(1)",
				DuckStore.parse(STORE.replace("active VARCHAR", "active VARCHAR(1)")).tableColumns().get("concept_s"));
	}

	@Test
	void theBundledStoreDeclaresOnlyPlainTables() throws IOException {
		// The checked-in store must still load: every table shape it carries is a
		// plain declaration of an accepted type.
		try (java.io.InputStream in = DuckStore.class.getResourceAsStream("/duck/store.json")) {
			assertTrue(DuckStore.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
					.tableColumns().size() > 0);
		}
	}
}
