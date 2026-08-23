/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.benchmark.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Finds and loads {@link BenchmarkProfile}s.
 *
 * <p>Profiles ship on the classpath under {@code profiles/} and can also be given as a path, so a
 * one-off question does not need a rebuild -- which is the whole reason the configuration is a file
 * format rather than Java.
 *
 * <p><b>Unknown properties are rejected.</b> A profile is hand-written and mostly consists of enum
 * names and numbers, so the realistic mistake is a typo -- {@code metrics: capped} under the wrong
 * key, {@code streamDesign: per_entity} misspelt. Ignoring those would silently run a different
 * benchmark from the one asked for and report it under the requested name, which is the worst
 * failure this suite could have. Failing to parse is cheap by comparison.
 */
public final class Profiles {

	/** Where profiles live on the classpath, and the directory name used inside a jar. */
	public static final String CLASSPATH_DIRECTORY = "profiles";

	private static final String SUFFIX = ".yaml";

	private static final ObjectMapper YAML = YAMLMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			// enum constants are unique case-insensitively, so accepting 'per_entity' for PER_ENTITY
			// costs no ambiguity and spares every profile author a round trip.  It does not weaken the
			// strictness above: a *misspelled* enum still fails, and the message lists the valid values.
			.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
			.build();

	/** Used only to render a loaded profile back for the report, never to read one. */
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private Profiles ( ) { }

	/**
	 * Loads a profile by name, from the classpath.
	 *
	 * @throws IllegalArgumentException if no profile of that name exists, listing the ones that do --
	 *         a mistyped profile name is the most likely way to invoke this wrongly, and an error that
	 *         names the alternatives costs nothing to produce
	 */
	public static BenchmarkProfile byName ( String name ) {
		String resource = CLASSPATH_DIRECTORY + "/" + name + SUFFIX;
		try ( InputStream stream = Profiles.class.getClassLoader().getResourceAsStream(resource) ) {
			if ( stream == null ) {
				throw new IllegalArgumentException("no profile named '%s'; available profiles are %s"
						.formatted(name, String.join(", ", available())));
			}
			return parse(stream, name, resource);
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not read profile '%s'".formatted(name), e);
		}
	}

	/** Loads a profile from a file, for a run whose configuration is not worth committing. */
	public static BenchmarkProfile fromFile ( Path path ) {
		if ( !Files.isReadable(path) ) {
			throw new IllegalArgumentException("profile file is not readable: " + path);
		}
		try ( InputStream stream = Files.newInputStream(path) ) {
			String name = path.getFileName().toString();
			return parse(stream, name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name,
					path.toString());
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not read profile file " + path, e);
		}
	}

	/**
	 * Accepts either form: a bare name resolved on the classpath, or a path to a file. A value that
	 * ends in {@code .yaml} or contains a separator is treated as a path.
	 */
	public static BenchmarkProfile resolve ( String nameOrPath ) {
		if ( nameOrPath == null || nameOrPath.isBlank() ) {
			throw new IllegalArgumentException("a profile name or path is required");
		}
		boolean looksLikePath = nameOrPath.endsWith(SUFFIX) || nameOrPath.contains("/") || nameOrPath.contains("\\");
		return looksLikePath ? fromFile(Path.of(nameOrPath)) : byName(nameOrPath);
	}

	private static BenchmarkProfile parse ( InputStream stream, String name, String origin ) {
		BenchmarkProfile parsed;
		try {
			parsed = YAML.readValue(stream, BenchmarkProfile.class);
		} catch ( RuntimeException e ) {
			throw new IllegalArgumentException("could not parse profile at %s: %s".formatted(origin, e.getMessage()), e);
		}
		if ( parsed.name() != null && !parsed.name().equals(name) ) {
			// the name is what reports and committed baseline directories are keyed on, so a file whose
			// contents disagree with its filename would produce results filed under the wrong question
			throw new IllegalArgumentException(
					"profile at %s declares name '%s' but is loaded as '%s'; they must match"
							.formatted(origin, parsed.name(), name));
		}
		return parsed;
	}

	/** The names of every profile on the classpath, sorted. */
	public static List<String> available ( ) {
		TreeSet<String> names = new TreeSet<>();
		try {
			var resources = Profiles.class.getClassLoader().getResources(CLASSPATH_DIRECTORY);
			while ( resources.hasMoreElements() ) {
				collectFrom(resources.nextElement(), names);
			}
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not enumerate profiles on the classpath", e);
		}
		return List.copyOf(names);
	}

	/**
	 * Enumerating a classpath directory has to handle both layouts: a plain directory during
	 * development, and an entry inside the shaded jar in every real run.
	 */
	private static void collectFrom ( URL url, TreeSet<String> into ) throws IOException {
		switch ( url.getProtocol() ) {
			case "file" -> {
				Path directory = Path.of(URI.create(url.toString()));
				if ( Files.isDirectory(directory) ) {
					try ( var entries = Files.list(directory) ) {
						entries.map(p -> p.getFileName().toString())
								.filter(n -> n.endsWith(SUFFIX))
								.map(n -> n.substring(0, n.length() - SUFFIX.length()))
								.forEach(into::add);
					}
				}
			}
			case "jar" -> {
				String spec = url.toString();
				URI jar = URI.create(spec.substring(0, spec.indexOf("!")));
				try ( FileSystem fs = openJar(jar) ) {
					Path directory = fs.getPath(CLASSPATH_DIRECTORY);
					if ( Files.isDirectory(directory) ) {
						try ( var entries = Files.list(directory) ) {
							entries.map(p -> p.getFileName().toString())
									.filter(n -> n.endsWith(SUFFIX))
									.map(n -> n.substring(0, n.length() - SUFFIX.length()))
									.forEach(into::add);
						}
					}
				}
			}
			default -> {
				// nothing else is expected; listing is a convenience, so an unknown layout is not fatal
			}
		}
	}

	private static FileSystem openJar ( URI jar ) throws IOException {
		try {
			return FileSystems.newFileSystem(jar, Map.of());
		} catch ( java.nio.file.FileSystemAlreadyExistsException e ) {
			return FileSystems.getFileSystem(jar);
		}
	}

	/** A profile rendered as JSON, for embedding in a run's report. */
	public static String toJson ( BenchmarkProfile profile ) {
		return JSON.writeValueAsString(profile);
	}

	/** Loads every profile on the classpath, for {@code list} and for validating them all at once. */
	public static List<BenchmarkProfile> loadAll ( ) {
		List<BenchmarkProfile> profiles = new ArrayList<>();
		for ( String name : available() ) {
			profiles.add(byName(name));
		}
		return profiles;
	}

	/** Loads a profile if it exists, without throwing when it does not. */
	public static Optional<BenchmarkProfile> find ( String name ) {
		return available().contains(name) ? Optional.of(byName(name)) : Optional.empty();
	}
}
