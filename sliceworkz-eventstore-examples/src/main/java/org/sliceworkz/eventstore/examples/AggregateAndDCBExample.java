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
package org.sliceworkz.eventstore.examples;

import java.util.List;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.EventReference;
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
	
	// One stream, opened once per aggregate root: a handle is cheap and shares its serde, and a query
	// through the handle typed at the root answers events of that root, so no cast is needed anywhere
	private final EventStream<LearningDomainEvent> stream;
	private final EventStream<StudentDomainEvent> students;
	private final EventStream<CourseDomainEvent> courses;
	
	public AggregateAndDCBExample ( EventStore es ) {
		EventStreamId streamId = EventStreamId.forContext("learning");
		this.stream = es.getEventStream(streamId, LearningDomainEvent.class);
		this.students = es.getEventStream(streamId, StudentDomainEvent.class);
		this.courses = es.getEventStream(streamId, CourseDomainEvent.class);
	}
	
	public static void main ( String[] args ) {
		EventStore es = InMemoryEventStorage.newBuilder().buildStore();
		
		new AggregateAndDCBExample(es).scenario();
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
		
		s = loadStudent("123");
	}
	
	// The one description of a student's relevant facts, used by the read below and by the boundary of
	// the append. Written out in both places instead, the two drift the moment someone widens only the
	// read: the consistency boundary then silently narrows and the store reports success on an append
	// that should have conflicted. Nothing -- no type, no test, no runtime check -- catches that
	EventQuery studentQuery ( String studentId ) {
	    return EventQuery.forEvents(
	        EventTypesFilter.of(StudentDomainEvent.class),
	        Tags.of("student", studentId)
	    );
	}

	// Load aggregate from events. The sealed interface stands for every event type under it, and the
	// stream typed at that root maps exactly those types, so its events are the aggregate's own
	Student loadStudent(String studentId) {
	    Student student = new Student(studentId);
	    students.query(studentQuery(studentId)).forEach(student::when);
	    return student;
	}

	// Save events with optimistic locking. The criteria gets the query as built, with no until boundary:
	// an until on a criteria's filter matches nothing past it, so no event would ever be a new relevant
	// fact and every append would be admitted. The boundary is the reference, never an until
	void saveStudent(Student student, List<StudentDomainEvent> events) {
	    students.append(
	        AppendCriteria.of(studentQuery(student.studentId), student.lastEventReference()),
	        events.stream()
	            .map(e -> Event.of(e, Tags.of("student", student.studentId)))
	            .toList()
	    );
	}

	// Likewise for a course: one query, read by loadCourse and checked by saveCourse
	EventQuery courseQuery ( String courseId ) {
	    return EventQuery.forEvents(
	        EventTypesFilter.of(CourseDomainEvent.class),
	        Tags.of("course", courseId)
	    );
	}

	// Load aggregate from events
	Course loadCourse(String courseId) {
	    Course course = new Course(courseId);
	    courses.query(courseQuery(courseId)).forEach(course::when);
	    return course;
	}

	// Save events with optimistic locking
	void saveCourse(Course course, List<CourseDomainEvent> events) {
	    courses.append(
	        AppendCriteria.of(courseQuery(course.courseId), course.lastEventReference()),
	        events.stream()
	            .map(e -> Event.of(e, Tags.of("course", course.courseId)))
	            .toList()
	    );
	}
	
    public boolean subscribeStudentToCourse(String studentId, String courseId) {
    	
    	RegistrationDecisionModel dm = new RegistrationDecisionModel(studentId, courseId);
    	// remark: in practice, we would use additional decision models eg to determine if the studentId and the courseId exist at all. 

    	// pin the consistency boundary before reading: absent for an empty stream, which is a valid boundary,
    	// so a student or course without any history yet needs no special case
    	EventReference head = stream.head().orElse(null);
    	// the read is bounded at the head; the append below hands the criteria the same query UNBOUNDED,
    	// with the head as its expected last event. Passing the until form to AppendCriteria instead reads
    	// as the more careful of the two and is the one that turns optimistic locking off outright
    	stream.query(dm.getEventQuery().until(head)).forEach(dm::when);

        if ( dm.canSubscribe() ) {
            stream.append(
                    AppendCriteria.of(dm.getEventQuery(), head),
                    Event.of(
                        new StudentSubscribedToCourse(studentId, courseId),
                        Tags.of("student", studentId, "course", courseId)
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

sealed interface StudentDomainEvent extends LearningDomainEvent {
    record StudentRegistered(String name) implements StudentDomainEvent {}
    record StudentNameChanged(String name) implements StudentDomainEvent {}
    record StudentUnsubscribed() implements StudentDomainEvent {}
}

sealed interface CourseDomainEvent extends LearningDomainEvent {
    record CourseDefined(String name, int capacity) implements CourseDomainEvent {}
    record CourseCapacityUpdated(int newCapacity) implements CourseDomainEvent {}
    record CourseCancelled() implements CourseDomainEvent {}
}

class Course implements EventHandler<CourseDomainEvent> {
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

    private void apply(CourseDomainEvent event) {
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
        apply(event.data());
        this.lastEventReference = event.reference();
    }

    public EventReference lastEventReference() {
        return lastEventReference;
    }
}


class Student implements EventHandler<StudentDomainEvent> {
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
    private void apply(StudentDomainEvent event) {
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
        apply(event.data());
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
		EventQuery studentQuery = EventQuery.forTypes(StudentSubscribedToCourse.class).tagged("student", studentId);
		
		// this query will deliver all events linked to the course at hand, including subscriptions from other students
		EventQuery courseQuery = EventQuery.forTypes(CourseDefined.class, CourseCapacityUpdated.class, StudentSubscribedToCourse.class).tagged("course", courseId);
		
		// ask for all matching events (union query)
		return studentQuery.or(courseQuery);
	}
	

	@Override
	public void when(Event<LearningDomainEvent> event) {
		switch ( event.data() ) {
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
