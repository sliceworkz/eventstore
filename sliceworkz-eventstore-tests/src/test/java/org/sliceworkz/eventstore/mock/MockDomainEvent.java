package org.sliceworkz.eventstore.mock;

public sealed interface MockDomainEvent {
	
	public record FirstDomainEvent ( String value ) implements MockDomainEvent {
		
	}

	public record SecondDomainEvent ( String value ) implements MockDomainEvent {
		
	}

	public record ThirdDomainEvent ( String value ) implements MockDomainEvent {
		
	}

}
