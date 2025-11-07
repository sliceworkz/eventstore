package org.sliceworkz.eventstore.events;

public interface Upcast<HISTORICAL_EVENT,DOMAIN_EVENT> {

	DOMAIN_EVENT upcast ( HISTORICAL_EVENT historicalEvent );
	
	Class<DOMAIN_EVENT> targetType ( );
	
}
