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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Utility class that can be used to create a DataSource to use.
 * Only useful when passing a DataSource to the PostgresEventStorage.Builder. 
 */
public class DataSourceFactory {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(DataSourceFactory.class);
	
	private DataSourceFactory ( ) {
		
	}

	public static DataSource fromConfiguration ( ) {
		return fromConfiguration((Properties)null);
	}
	
	public static DataSource fromConfiguration ( Properties properties ) {
		return fromConfiguration(properties, null);
	}

	public static DataSource fromConfiguration ( String datasourceConfigurationName ) {
		return fromConfiguration(loadProperties(), datasourceConfigurationName);
	}
	
	public static DataSource fromConfiguration ( Properties dbProperties, String datasourceConfigurationName ) {
		HikariConfig config = HikariConfigurationUtil.createConfig(datasourceConfigurationName, dbProperties);
		if ( config != null ) {
			return new HikariDataSource(config);
		} else {
			return null;
		}
	}

	// looks for file in current working directory of process, and few levels up
	public static Properties loadProperties ( ) {

		String configPath = System.getProperty("eventstore.db.config");
		if ( configPath != null ) {
			LOGGER.info("database configuration config path configured via System property 'eventstore.db.config' ({})", configPath);
		} else {
			LOGGER.debug("database configuration config path not found via System property 'eventstore.db.config'");
		}
		
		// 2. Environment variable: EVENTSTORE_DB_CONFIG
		if (configPath == null) {
			configPath = System.getenv("EVENTSTORE_DB_CONFIG");
			if ( configPath != null ) {
				LOGGER.info("searching database configuration in config path via environment variable EVENTSTORE_DB_CONFIG 'eventstore.db.config' ({})", configPath);
			} else {
				LOGGER.debug("database configuration config path not found via environment variable EVENTSTORE_DB_CONFIG 'eventstore.db.config'");
			}
		}

		// 3. Fall back to file search
		if (configPath == null) {
		    configPath = findPropertiesFile();
			if ( configPath != null ) {
				LOGGER.info("searching database configuration in db.properties in pwd or parent folder(s) ({})", configPath);
			} else {
				LOGGER.error("database configuration not found in db.properties in pwd or parent folder(s)");
			}
		}

		Properties result = new Properties();
		try {
			try ( InputStream is = new FileInputStream(configPath)) {
				result.load(is);
			}
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return result;
	}
	
	static String findPropertiesFile ( ) {
		File file = null;
		for ( int i = 0; i < 3; i++ ) {
			file = new File("../".repeat(i) + "db.properties");
			if ( file.exists() ) {
				break;
			}
		}
		if (!file.exists() ) {
			throw new RuntimeException("db.properties file not found in current or parent directory up to 2 levels up");
		} else {
			LOGGER.info("read properties from '{}'", file);
		}
		return file.getPath();
	}

}
