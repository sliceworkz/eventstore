# EventStore

A DCB (Dynamic Context Boundary) compliant EventStore implementation in Java


## Dynamic Consistency Boundary compliant

Fully compliant will all features described on [DCB Specification](https://dcb.events/specification/).

- Tagging of Events for dynamic retrieval
- Optimistic locking via AppendCriteria


## Persistence
- In-memory	(development and demo purposes only)
- PostgreSQL


## Quickstart

We'll assume maven as a dependency manager and provide the required snippets to pom.xml 

### Add the eventstore maven repo

```xml
	<repositories> 
	    <repository>
	        <id>io.cloudrepo.xti.opensource</id>
	        <name>XTi open source repo</name>
	        <url>https://xti.mycloudrepo.io/public/repositories/opensource</url> 
	    </repository>
	</repositories>
```

### Add the eventstore dependencies

```xml
	...
	<dependencyManagement>
		<dependencies>
			<dependency>
				<groupId>org.sliceworkz</groupId>
				<artifactId>sliceworkz-eventstore-bom</artifactId>
				<version>${sliceworkz.eventstore.version}</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
		</dependencies>
	</dependencyManagement>

	<dependencies>
	
		<dependency>
			<groupId>org.sliceworkz</groupId>
			<artifactId>sliceworkz-eventstore-api</artifactId>
		</dependency>
		<dependency>
			<groupId>org.sliceworkz</groupId>
			<artifactId>sliceworkz-eventstore-impl</artifactId>
		</dependency>
		<dependency>
			<groupId>org.sliceworkz</groupId>
			<artifactId>sliceworkz-eventstore-infra-inmem</artifactId>
		</dependency>
		
	</dependencies>
	...
```

### Write some code

Or have a look in the examples (sliceworkz-eventstore-examples/) sources.


```java
package org.sliceworkz.eventstore.examples;

import java.util.stream.Stream;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerChurned;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerNameChanged;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class AppendAndQueryAllExample {
	
	public static void main ( String[] args ) {
		
		// create a memory-backed eventstore
		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

		// create an EventStream for a particular customer 
		EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
		EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);
		
		// append 3 individual events to the stream
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new CustomerNameChanged("Jane"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new CustomerChurned(), Tags.none()));
		
		// query and print all events that are now in the stream
		Stream<Event<CustomerEvent>> allEvents = stream.query(EventQuery.matchAll());
		allEvents.forEach(System.out::println);
		
	}
	
	sealed interface CustomerEvent {
		
		public record CustomerRegistered ( String name ) implements CustomerEvent { }
		
		public record CustomerNameChanged (String name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
	}

}
```
