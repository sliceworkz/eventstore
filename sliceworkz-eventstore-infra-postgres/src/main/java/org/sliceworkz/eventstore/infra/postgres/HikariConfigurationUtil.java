package org.sliceworkz.eventstore.infra.postgres;

import java.lang.reflect.Method;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;

/*
 * Example config file (pooled and non-pooled configuration, as LISTEN/NOTIFY doesnt work with pgbouncer):

db.pooled.url=jdbc:postgresql://host-pooler.domain.com/db
db.pooled.username=username
db.pooled.password=password
db.pooled.leakDetectionThreshold=2000
db.pooled.maximumPoolSize=25
db.pooled.datasource.sslmode=require
db.pooled.datasource.channelBinding=require
db.pooled.datasource.cachePrepStmts=true
db.pooled.datasource.prepStmtCacheSize=250
db.pooled.datasource.prepStmtCacheSqlLimit=2048

db.pooled.url=jdbc:postgresql://hos.domain.com/db
db.nonpooled.username=username
db.nonpooled.password=password
db.nonpooled.leakDetectionThreshold=60000
db.nonpooled.maximumPoolSize=2
db.nonpooled.datasource.sslmode=require
db.nonpooled.datasource.channelBinding=require
db.nonpooled.datasource.cachePrepStmts=true
db.nonpooled.datasource.prepStmtCacheSize=250
db.nonpooled.datasource.prepStmtCacheSqlLimit=2048

 */
public class HikariConfigurationUtil {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(HikariConfigurationUtil.class);
    
    /**
     * Creates and populates a HikariConfig from Properties object
     * 
     * @param connectionName the name of the connection (e.g., "pooled", "nonpooled")
     * @param props the Properties object containing configuration
     * @return configured HikariConfig instance
     */
    public static HikariConfig createConfig(String connectionName, Properties props) {
        HikariConfig config = new HikariConfig();
        String prefix = "db." + connectionName + ".";
        String datasourcePrefix = prefix + "datasource.";
        
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            
            String value = props.getProperty(key);
            
            if (key.startsWith(datasourcePrefix)) {
                // Handle datasource properties
                String dsPropertyName = key.substring(datasourcePrefix.length());
                config.addDataSourceProperty(dsPropertyName, value);
            } else {
                // Handle HikariConfig properties
                String propertyName = key.substring(prefix.length());
                setHikariProperty(config, propertyName, value);
            }
        }
        
        return config;
    }
    
    /**
     * Sets a property on HikariConfig using reflection to find the appropriate setter
     */
    private static void setHikariProperty(HikariConfig config, String propertyName, String value) {
        // Convert property name to setter method name (e.g., "url" -> "setJdbcUrl", "username" -> "setUsername")
        String methodName = "set" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        
        // Special case: "url" maps to "setJdbcUrl"
        if ("url".equals(propertyName)) {
            methodName = "setJdbcUrl";
        }
        
        try {
            // Try to find the setter method with String parameter
            Method method = findSetterMethod(config.getClass(), methodName);
            
            if (method != null) {
                Class<?> paramType = method.getParameterTypes()[0];
                Object convertedValue = convertValue(value, paramType);
                method.invoke(config, convertedValue);
            } else {
                LOGGER.warn("Warning: No setter found for property: " + propertyName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error setting property " + propertyName + " to value " + value, e);
        }
    }
    
    /**
     * Finds a setter method by name, looking for String, long, or int parameter types
     */
    private static Method findSetterMethod(Class<?> clazz, String methodName) {
        // Try String parameter first
        try {
            return clazz.getMethod(methodName, String.class);
        } catch (NoSuchMethodException e) {
            // Continue to try other types
        }
        
        // Try long parameter
        try {
            return clazz.getMethod(methodName, long.class);
        } catch (NoSuchMethodException e) {
            // Continue to try other types
        }
        
        // Try int parameter
        try {
            return clazz.getMethod(methodName, int.class);
        } catch (NoSuchMethodException e) {
            // Continue to try other types
        }
        
        // Try boolean parameter
        try {
            return clazz.getMethod(methodName, boolean.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
    
    /**
     * Converts string value to appropriate type
     */
    private static Object convertValue(String value, Class<?> targetType) {
        if (targetType == String.class) {
            return value;
        } else if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value);
        } else if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value);
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }
    
}