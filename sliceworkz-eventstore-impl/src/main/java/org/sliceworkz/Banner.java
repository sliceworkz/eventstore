package org.sliceworkz;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Banner {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Banner.class);

	public static void printBanner ( ) {
		
		try ( BufferedReader br = new BufferedReader(new InputStreamReader(Banner.class.getClassLoader().getResourceAsStream("banner.txt"))) ) {
			
			StringBuilder sb = new StringBuilder();
			sb.append("\n");
			String line = br.readLine();
			while ( line != null ) {
				sb.append(line);
				sb.append("\n");
				line = br.readLine();
			}

			LOGGER.info(sb.toString());
			
		} catch ( IOException e ) {
			throw new RuntimeException(e);
		}
		
	}
			
}
