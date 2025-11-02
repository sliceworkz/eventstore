package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class PostgresEventStorageImplTest {
	
	@Test
	void testPrefixNull ( ) {
		String prefix = null;
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixCorrect ( ) {
		String prefix = "tenant_";
		PostgresEventStorageImpl.validatePrefix(prefix);
	}

	@Test
	void testPrefixEmpty ( ) {
		String prefix = "";
		PostgresEventStorageImpl.validatePrefix(prefix);
	}

	@Test
	void testPrefixSpace( ) {
		String prefix = " ";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixLetter( ) {
		String prefix = "a";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixNoUnderscore( ) {
		String prefix = "tenant";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixUnderscore ( ) {
		String prefix = "_";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}
	
}
