package org.sliceworkz.eventstore.infra.postgres;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.spi.EventStorage;

public interface PostgresEventStorage {
	
	public static Builder newBuilder ( ) {
		return PostgresEventStorage.Builder.newBuilder();
	}
	
	public static class Builder {
		
		private String prefix = "";
		private String name = "psql";
		private DataSource dataSource;
		private DataSource monitoringDataSource;
		private boolean initializeDatabase = false;
		
		private Builder ( ) {
			
		}
		
		static Builder newBuilder ( ) {
			return new Builder ( );
		}
		
		public Builder name ( String name ) {
			this.name = name;
			return this;
		}
		
		public Builder dataSource ( DataSource dataSource ) {
			this.dataSource = dataSource;
			this.monitoringDataSource = dataSource;
			return this;
		}

		public Builder monitoringDataSource ( DataSource monitoringDataSource ) {
			this.monitoringDataSource = monitoringDataSource;
			return this;
		}

		public Builder prefix ( String prefix ) {
			this.prefix = prefix;
			return this;
		}
		
		public Builder initializeDatabase ( ) {
			return initializeDatabase(true);
		}

		public Builder initializeDatabase ( boolean value ) {
			this.initializeDatabase = value;
			return this;
		}

		public EventStorage build ( ) {
			if ( dataSource == null ) {
				dataSource = DataSourceFactory.fromConfiguration("pooled");
				monitoringDataSource = DataSourceFactory.fromConfiguration("nonpooled");
			}
			var result = new PostgresEventStorageImpl(name, dataSource, monitoringDataSource, prefix);
			if ( initializeDatabase ) {
				result.initializeDatabase();
			}
			return result;
			
		}
		
		public EventStore buildStore ( ) {
			return EventStoreFactory.get().eventStore(build());
		}
		
	}

}
