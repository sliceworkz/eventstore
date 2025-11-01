package org.sliceworkz.eventstore.mock;

public interface MockDomainEventWithNonSealedInterface {
	
	public record DomainEventPartOfMockDomainEventWithNonSealedInterface (  ) implements MockDomainEventWithNonSealedInterface { }
	
}
