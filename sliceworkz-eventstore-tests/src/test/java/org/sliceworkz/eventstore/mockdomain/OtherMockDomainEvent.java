package org.sliceworkz.eventstore.mockdomain;

public sealed interface OtherMockDomainEvent {

	public record AnotherDomainEvent ( String value ) implements OtherMockDomainEvent { } 
	

}
