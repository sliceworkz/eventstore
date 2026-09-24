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
package org.sliceworkz.eventstore.infra.postgres;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.observability.NotificationChannel;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.observability.Outcome;

/**
 * An observer that keeps the last reported state of each notification channel, which is what the
 * storage's health signal is: {@code 1} while a channel is reported up, {@code 0} while it is reported
 * down, {@code NaN} for a channel never reported at all.
 */
final class NotificationChannels implements EventStoreObserver {

	private final Map<NotificationChannel, Boolean> listening = new ConcurrentHashMap<>();

	@Override
	public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
		return NOOP.start(observation);
	}

	@Override
	public void notificationChannelChanged ( String storage, NotificationChannel channel, boolean up ) {
		listening.put(channel, up);
	}

	double state ( String channel ) {
		Boolean up = listening.get(NotificationChannel.valueOf(channel.toUpperCase()));
		return up == null ? Double.NaN : ( up ? 1d : 0d );
	}

}
