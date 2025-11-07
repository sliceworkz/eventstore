package org.sliceworkz.eventstore.mockdomain;

public sealed interface MockDomainDuplicatedEvent {

	public record FirstDomainEvent ( String value ) implements MockDomainDuplicatedEvent { } 
	
	public record SecondDomainEvent ( String value ) implements MockDomainDuplicatedEvent { } 

	public record ThirdDomainEvent ( String value ) implements MockDomainDuplicatedEvent { } 

}

