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
package org.sliceworkz.eventstore.impl.serde;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;

import org.sliceworkz.eventstore.events.Erasable;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.PartlyErasable;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Abstract base class for event payload serializers/deserializers providing common Jackson-based functionality.
 * <p>
 * This class implements the core serialization logic that splits event data into immutable and erasable parts
 * to support GDPR compliance. It uses Jackson's JSON view mechanism combined with custom annotation introspection
 * to automatically categorize fields based on {@link Erasable} and {@link PartlyErasable} annotations.
 * <p>
 * Two Jackson {@link JsonMapper} instances are maintained:
 * <ul>
 *   <li><b>immutableDataMapper:</b> Serializes fields marked for the {@code ImmutableData} view</li>
 *   <li><b>erasableDataMapper:</b> Serializes only fields marked for the {@code ErasableData} view,
 *       excluding immutable fields via {@code DEFAULT_VIEW_INCLUSION} configuration</li>
 * </ul>
 * <p>
 * The automatic field categorization works as follows:
 * <ul>
 *   <li>Fields without annotations: included in immutable data only</li>
 *   <li>Fields with {@code @Erasable}: included in erasable data only</li>
 *   <li>Fields with {@code @PartlyErasable}: included in both immutable and erasable data</li>
 * </ul>
 * <p>
 * This implementation properly handles Java records by inspecting record components rather than just methods,
 * ensuring annotations on record component declarations are correctly detected.
 *
 * <h2>GDPR Compliance:</h2>
 * The split between immutable and erasable data enables the "right to be forgotten" by allowing
 * selective deletion of personal data while retaining the event structure for audit trails.
 *
 * @see TypedEventPayloadSerializerDeserializer
 * @see RawEventPayloadSerializerDeserializer
 * @see Erasable
 * @see PartlyErasable
 */
public abstract class AbstractEventPayloadSerializerDeserializer implements EventPayloadSerializerDeserializer {
	
	protected JsonMapper immutableDataMapper;
	protected JsonMapper erasableDataMapper;
	protected ErasableAnnotationIntrospector introspector = new ErasableAnnotationIntrospector();

	
	public AbstractEventPayloadSerializerDeserializer (  ) {
		this.immutableDataMapper = JsonMapper.builder().build();
		this.immutableDataMapper.findAndRegisterModules();
		this.erasableDataMapper = JsonMapper.builder().disable(MapperFeature.DEFAULT_VIEW_INCLUSION).build();
		this.erasableDataMapper.findAndRegisterModules();
	}
	
	@Override
	public TypeAndSerializedPayload serialize ( Object payload ) {
		String immutableData = null;
		String erasableData = null;
		try {

			immutableData = immutableDataMapper
					.setAnnotationIntrospector(introspector)
	                .writerWithView(JsonViewTags.ImmutableData.class)
					.writeValueAsString(payload);
			
	        erasableData = erasableDataMapper
					.setAnnotationIntrospector(introspector)
	                .writerWithView(JsonViewTags.ErasableData.class)
	                .writeValueAsString(payload);
	        
	        // check if erasableData is empty, set to null in that case 
	        if ( erasableData.length() < 3 ) { // "{}" (empty json)
	        	erasableData = null;
	        }
	        
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize event data", e);
		}
		return new TypeAndSerializedPayload(EventType.of(payload), immutableData, erasableData);
	}

	protected void deepMerge(ObjectNode target, ObjectNode source) {
		source.properties().forEach(entry -> {
			String key = entry.getKey();
			var value = entry.getValue();
			
			if (value.isObject() && target.has(key) && target.get(key).isObject()) {
				// Recursively merge nested objects
				deepMerge((ObjectNode) target.get(key), (ObjectNode) value);
			} else {
				// Replace or add the field
				target.set(key, value);
			}
		});
	}
	
	// Custom introspector that automatically assigns views to split between Immutable and Erasable data
	public static class ErasableAnnotationIntrospector extends JacksonAnnotationIntrospector {
		
		@Override
		public Class<?>[] findViews(Annotated member) {
			// Check for explicit @JsonView first
			Class<?>[] views = super.findViews(member);
			if (views != null) {
				return views;
			}

			Erasable annotatedWithErasable = findAnnotationOnMember(member, Erasable.class);
			PartlyErasable annotatedWithPartlyErasable = findAnnotationOnMember(member, PartlyErasable.class);
			
			if (annotatedWithErasable == null && annotatedWithPartlyErasable == null) {
				return new Class<?>[] { JsonViewTags.ImmutableData.class };
				
			} else if ( annotatedWithPartlyErasable != null ) {
				return new Class<?>[] { JsonViewTags.ImmutableData.class, JsonViewTags.ErasableData.class };
				
			} else {
				return new Class<?>[] { JsonViewTags.ErasableData.class };
			}
		}
	}

	

	private static <A extends Annotation> A findAnnotationOnMember(Annotated member, Class<A> annotationType) {
	    // First check if the member has the annotation directly
	    A annotation = member.getAnnotation(annotationType);
	    if (annotation != null) {
	        return annotation;
	    }
	    
	    AnnotatedElement element = member.getAnnotated();
	    if (element != null) {
	        // For records, Jackson introspects methods but annotations are on record components
	        if (element instanceof java.lang.reflect.Method) {
	            java.lang.reflect.Method method = (java.lang.reflect.Method) element;
	            Class<?> declaringClass = method.getDeclaringClass();
	            
	            // Check if this is a record
	            if (declaringClass.isRecord()) {
	                // Find the corresponding record component
	                try {
	                    java.lang.reflect.RecordComponent component = 
	                        java.util.Arrays.stream(declaringClass.getRecordComponents())
	                            .filter(rc -> rc.getName().equals(method.getName()))
	                            .findFirst()
	                            .orElse(null);
	                    
	                    if (component != null) {
	                        // Check direct annotation on record component
	                        annotation = component.getAnnotation(annotationType);
	                        if (annotation != null) {
	                            return annotation;
	                        }
	                        
	                        // Check meta-annotations on record component
	                        for (Annotation ann : component.getAnnotations()) {
	                            A metaAnnotation = ann.annotationType().getAnnotation(annotationType);
	                            if (metaAnnotation != null) {
	                                return metaAnnotation;
	                            }
	                        }
	                    }
	                } catch (Exception e) {
	                    // Fall through to regular handling
	                }
	            }
	        }
	        
	        // Fallback: search through annotations on the element itself
	        for (Annotation ann : element.getAnnotations()) {
	            A metaAnnotation = ann.annotationType().getAnnotation(annotationType);
	            if (metaAnnotation != null) {
	                return metaAnnotation;
	            }
	        }
	    }
	    
	    return null;
	}
	
	static class JsonViewTags {
		public static class ImmutableData {}
		public static class ErasableData {}
	}
	
}
