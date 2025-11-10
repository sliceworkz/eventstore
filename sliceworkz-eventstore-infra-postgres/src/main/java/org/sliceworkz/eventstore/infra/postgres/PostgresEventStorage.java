/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventstore.infra.postgres;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.query.Limit;
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
		private Limit limit = Limit.none();

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
		
		public Builder resultLimit ( int absoluteLimit ) {
			this.limit = Limit.to(absoluteLimit);
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
			var result = new PostgresEventStorageImpl(name, dataSource, monitoringDataSource, limit, prefix);
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
