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
package org.sliceworkz.eventstore.examples;

import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventWithMetaDataHandler;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.CourseDomainEvent.CourseCapacityUpdated;
import org.sliceworkz.eventstore.examples.CourseDomainEvent.CourseDefined;
import org.sliceworkz.eventstore.examples.RegistrationDomainEvent.StudentSubscribedToCourse;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Very simple example on how DCB can be used in combination with classic Aggregates.
 * 
 * DISCLAIMER: This code only shows capabilities - don't use as it as a reference for your own implementation - it's absolutely not meant as a best-practice code template.
 */
public class AggregateAndDCBExample {
	
	private EventStream<LearningDomainEvent> stream;
	
	public AggregateAndDCBExample ( EventStream<LearningDomainEvent> stream ) {
		this.stream = stream;
	}
	
	public static void main ( String[] args ) {
		EventStore es = InMemoryEventStorage.newBuilder().buildStore();
		
		EventStream<LearningDomainEvent> stream = es.getEventStream(EventStreamId.forContext("learning"), LearningDomainEvent.class);
		
		new AggregateAndDCBExample(stream).scenario();
	}
	
	void scenario ( ) {
		Student s = loadStudent("123");
		saveStudent(s, s.register("Jane"));
		
		s = loadStudent("123");
		saveStudent(s, s.changeName("Jane Doe"));
		
		Course c = loadCourse("abc001");
		saveCourse(c, c.define("Java basics", 12));
		
		System.out.println("1st subscription attempt: %b".formatted(subscribeStudentToCourse("123", "abc001"))); // returns true as subscription succeeds
		System.out.println("2nd subscription attempt: %b".formatted(subscribeStudentToCourse("123", "abc001"))); // returns false as subscription fails, since this student is already subscribed to this course
	}
	
	// Load aggregate from events
	Student loadStudent(String studentId) {
	    Student student = new Student(studentId);
	    EventQuery query = EventQuery.forEvents(
	        EventTypesFilter.any(),
	        Tags.of("student", studentId)
	    );
	    stream.query(query)
	    	.forEach(event -> student.when(event.cast()));
	    return student;
	}

	// Save events with optimistic locking
	void saveStudent(Student student, List<StudentDomainEvent> events) {
	    stream.append(
	        AppendCriteria.of(
	            EventQuery.forEvents(
	                EventTypesFilter.any(),
	                Tags.of("student", student.studentId)
	            ),
	            Optional.ofNullable(student.lastEventReference())
	        ),
	        events.stream()
	            .<EphemeralEvent<? extends LearningDomainEvent>>map(e -> Event.of(e, Tags.of("student", student.studentId)))
	            .toList()
	    );
	}

	// Load aggregate from events
	Course loadCourse(String courseId) {
	    Course course = new Course(courseId);
	    EventQuery query = EventQuery.forEvents(
	        EventTypesFilter.any(),
	        Tags.of("course", courseId)
	    );
	    stream.query(query)
	    	.forEach(event -> course.when(event.cast()));
	    return course;
	}

	// Save events with optimistic locking
	void saveCourse(Course course, List<CourseDomainEvent> events) {
	    stream.append(
	        AppendCriteria.of(
	            EventQuery.forEvents(
	                EventTypesFilter.any(),
	                Tags.of("course", course.courseId)
	            ),
	            Optional.ofNullable(course.lastEventReference())
	        ),
	        events.stream()
	            .<EphemeralEvent<? extends LearningDomainEvent>>map(e -> Event.of(e, Tags.of("course", course.courseId)))
	            .toList()
	    );
	}
	
    public boolean subscribeStudentToCourse(String studentId, String courseId) {
    	
    	RegistrationDecisionModel dm = new RegistrationDecisionModel(studentId, courseId);
    	// remark: in practice, we would use additional decision models eg to determine if the studentId and the courseId exist at all. 

    	List<Event<LearningDomainEvent>> relevantEvents = stream.query(dm.getEventQuery()).toList();
        EventReference lastRef = relevantEvents.getLast().reference();
        relevantEvents.forEach(dm::when);

        if ( dm.canSubscribe() ) {
            stream.append(
                    AppendCriteria.of(dm.getEventQuery(), Optional.ofNullable(lastRef)),
                    Event.of(
                        new StudentSubscribedToCourse(studentId, courseId),
                        Tags.of(Tag.of("student", studentId), Tag.of("course", courseId))
                    )
                );
            return true;
        } else {
        	// can't subscribe; get the details, throw an error, ...
        	return false;
        }
        
    }

}

sealed interface LearningDomainEvent {}

sealed interface RegistrationDomainEvent extends LearningDomainEvent {
    record StudentSubscribedToCourse(String studentId, String courseId)
        implements RegistrationDomainEvent {}
}

// Extend hierarchies to include registration events
sealed interface StudentDomainEvent extends LearningDomainEvent {
    record StudentRegistered(String name) implements StudentDomainEvent {}
    record StudentNameChanged(String name) implements StudentDomainEvent {}
    record StudentUnsubscribed() implements StudentDomainEvent {}
    // Inherits StudentSubscribedToCourse from RegistrationEvents
}

sealed interface CourseDomainEvent extends LearningDomainEvent {
    record CourseDefined(String name, int capacity) implements CourseDomainEvent {}
    record CourseCapacityUpdated(int newCapacity) implements CourseDomainEvent {}
    record CourseCancelled() implements CourseDomainEvent {}
    // Inherits StudentSubscribedToCourse from RegistrationEvents
}

class Course implements EventWithMetaDataHandler<CourseDomainEvent> {
    String courseId;
    String name;
    int capacity;
    boolean active;
    EventReference lastEventReference;

    public Course(String courseId) {
        this.courseId = courseId;
    }

    public List<CourseDomainEvent> define(String name, int capacity) {
        if (active) {
            throw new IllegalStateException("Course already defined");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        return List.of(new CourseDomainEvent.CourseDefined(name, capacity));
    }

    public List<CourseDomainEvent> updateCapacity(int newCapacity) {
        if (!active) {
            throw new IllegalStateException("Course not active");
        }
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (this.capacity == newCapacity) {
            return List.of();
        }
        return List.of(new CourseDomainEvent.CourseCapacityUpdated(newCapacity));
    }

    public List<CourseDomainEvent> cancel() {
        if (!active) {
            throw new IllegalStateException("Course already cancelled");
        }
        return List.of(new CourseDomainEvent.CourseCancelled());
    }

    private void when(CourseDomainEvent event) {
        switch(event) {
            case CourseDomainEvent.CourseDefined d -> {
                this.name = d.name();
                this.capacity = d.capacity();
                this.active = true;
            }
            case CourseDomainEvent.CourseCapacityUpdated u -> {
                this.capacity = u.newCapacity();
            }
            case CourseDomainEvent.CourseCancelled c -> {
                this.active = false;
            }
        }
    }

    @Override
    public void when(Event<CourseDomainEvent> event) {
        when(event.data());
        this.lastEventReference = event.reference();
    }

    public EventReference lastEventReference() {
        return lastEventReference;
    }
}


class Student implements EventWithMetaDataHandler<StudentDomainEvent> {
    String studentId;
    String name;
    boolean active;
    EventReference lastEventReference;

    public Student(String studentId) {
        this.studentId = studentId;
    }

    // Command handlers - check invariants and produce events
    public List<StudentDomainEvent> register(String name) {
        if (active) {
            throw new IllegalStateException("Student already registered");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }
        return List.of(new StudentDomainEvent.StudentRegistered(name));
    }

    public List<StudentDomainEvent> changeName(String newName) {
        if (!active) {
            throw new IllegalStateException("Student not active");
        }
        if (name.equals(newName)) {
            return List.of(); // No change
        }
        return List.of(new StudentDomainEvent.StudentNameChanged(newName));
    }

    public List<StudentDomainEvent> unsubscribe() {
        if (!active) {
            throw new IllegalStateException("Student already unsubscribed");
        }
        return List.of(new StudentDomainEvent.StudentUnsubscribed());
    }

    // Event handlers - apply state changes
    private void when (StudentDomainEvent event) {
        switch(event) {
            case StudentDomainEvent.StudentRegistered r -> {
                this.name = r.name();
                this.active = true;
            }
            case StudentDomainEvent.StudentNameChanged n -> {
                this.name = n.name();
            }
            case StudentDomainEvent.StudentUnsubscribed u -> {
                this.active = false;
            }
        }
    }

    @Override
    public void when(Event<StudentDomainEvent> event) {
        when(event.data());
        this.lastEventReference = event.reference();
    }

    public EventReference lastEventReference() {
        return lastEventReference;
    }
}

class RegistrationDecisionModel implements EventHandler<LearningDomainEvent> {
	
	private String studentId;
	private String courseId;
	
	private boolean studentAlreadySubscribed;
	private int studentSubscriptions;
	private int courseSubsciptions;
	private int courseCapacity;
	
	public RegistrationDecisionModel ( String studentId, String courseId ) {
		this.studentId = studentId;
		this.courseId = courseId;
	}
	
	public EventQuery getEventQuery ( ) {
		// this query will deliver all events linked to the student at hand, including subscriptions to other courses
		EventQuery studentQuery = EventQuery.forEvents(EventTypesFilter.of(StudentSubscribedToCourse.class), Tags.of("student", studentId));
		
		// this query will deliver all events linked to the course at hand, including subscriptions from other students
		EventQuery courseQuery = EventQuery.forEvents(EventTypesFilter.of(CourseDefined.class, CourseCapacityUpdated.class, StudentSubscribedToCourse.class), Tags.of("course", courseId));
		
		// ask for all matching events (union query)
		return studentQuery.combineWith(courseQuery);
	}
	

	@Override
	public void when(LearningDomainEvent event) {
		switch ( event ) {
			case RegistrationDomainEvent.StudentSubscribedToCourse s -> {
				if ( s.studentId().equals(studentId) ) {
					studentSubscriptions++; 
				}
				if ( s.courseId().equals(courseId) ) {
					courseSubsciptions++;
				}
				if ( s.studentId().equals(studentId) && s.courseId().equals(courseId) ) {
					studentAlreadySubscribed = true;
				}
			} 
			case CourseDomainEvent.CourseDefined d -> { 
				courseCapacity = d.capacity();
			} 
			case CourseDomainEvent.CourseCapacityUpdated u -> { 
				courseCapacity = u.newCapacity();
			} 
			default -> { 
				// not of interest to our decision
			}
		}
	}
	
	public boolean canSubscribe ( ) {
		boolean result = true;
		if ( studentAlreadySubscribed ) {
			System.out.println("already subscribed to this course");
			result = false; // student is already subscribed to this course
		}
		if ( studentSubscriptions >= 5 ) {
			System.out.println("student already subscribed to 5 courses");
			result = false; // student is already subscribed to 5 (other) courses
		}
		if ( courseSubsciptions >= courseCapacity ) {
			System.out.println("course already at capacity");
			result = false; // course has already reached its maximum capacity
		}
		return result;
	}
	
}
