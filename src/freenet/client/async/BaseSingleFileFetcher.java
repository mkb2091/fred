/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.Collections;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.KeysFetchingLocally;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.support.Executor;
import freenet.support.Logger;

public abstract class BaseSingleFileFetcher extends SendableGet implements HasKeyListener {

	final ClientKey key;
	protected boolean cancelled;
	protected boolean finished;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	static final Object[] keys = new Object[] { new Integer(0) };
	/** It is essential that we know when the cooldown will end, otherwise we cannot 
	 * remove the key from the queue if we are killed before that */
	long cooldownWakeupTime;
	private boolean chosen;

	protected BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent) {
		super(parent);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Creating BaseSingleFileFetcher for "+key);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		cooldownWakeupTime = -1;
	}

	@Override
	public Object[] allKeys(ObjectContainer container) {
		return keys;
	}
	
	@Override
	public Object[] sendableKeys(ObjectContainer container) {
		return keys;
	}
	
	@Override
	public Object chooseKey(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		if(fetching.hasKey(key.getNodeKey())) return null;
		if(chosen) return null;
		chosen = true;
		if(persistent)
			container.store(this);
		return keys[0];
	}
	
	@Override
	public boolean hasValidKeys(KeysFetchingLocally fetching, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		if(chosen) return false;
		return !fetching.hasKey(key.getNodeKey());
	}
	
	@Override
	public ClientKey getKey(Object token, ObjectContainer container) {
		if(persistent)
			container.activate(key, 5);
		return key;
	}
	
	@Override
	public FetchContext getContext() {
		return ctx;
	}

	@Override
	public boolean isSSK() {
		return key instanceof ClientSSK;
	}

	/** Try again - returns true if we can retry 
	 * @param sched */
	protected boolean retry(ObjectContainer container, ClientContext context) {
		retryCount++;
		chosen = false;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+retryCount+ ')');
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		if((retryCount <= maxRetries) || (maxRetries == -1)) {
			if(persistent)
				container.store(this);
			if(retryCount % RequestScheduler.COOLDOWN_RETRIES == 0) {
				// Add to cooldown queue. Don't reschedule yet.
				long now = System.currentTimeMillis();
				if(cooldownWakeupTime > now)
					Logger.error(this, "Already on the cooldown queue for "+this, new Exception("error"));
				else {
					if(persistent)
						container.activate(key, 5);
					RequestScheduler sched = context.getFetchScheduler(key instanceof ClientSSK);
					cooldownWakeupTime = sched.queueCooldown(key, this, container);
					if(persistent)
						container.deactivate(key, 5);
				}
			} else {
				reschedule(container, context);
			}
			return true; // We will retry in any case, maybe not just not yet. See requeueAfterCooldown(Key).
		}
		return false;
	}

	@Override
	public int getRetryCount() {
		return retryCount;
	}

	@Override
	public ClientRequester getClientRequest() {
		return parent;
	}

	@Override
	public short getPriorityClass(ObjectContainer container) {
		if(persistent) container.activate(parent, 1); // Not much point deactivating it
		short retval = parent.getPriorityClass();
		return retval;
	}

	@Override
	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			cancelled = true;
		}
		if(persistent) {
			container.store(this);
			container.activate(key, 5);
		}
		
		unregisterAll(container, context);
	}
	
	/**
	 * Remove the pendingKeys item and then remove from the queue as well.
	 * Call unregister(container) if you only want to remove from the queue.
	 */
	public void unregisterAll(ObjectContainer container, ClientContext context) {
		getScheduler(context).removePendingKeys(this, false);
		super.unregister(container, context);
	}

	@Override
	public synchronized boolean isCancelled(ObjectContainer container) {
		return cancelled;
	}
	
	public synchronized boolean isEmpty(ObjectContainer container) {
		return cancelled || finished || chosen;
	}
	
	@Override
	public RequestClient getClient() {
		return parent.getClient();
	}

	public boolean dontCache(ObjectContainer container) {
		return !ctx.cacheLocalRequests;
	}
	
	@Override
	public boolean dontCache() {
		return !ctx.cacheLocalRequests;
	}
	
	public boolean canRemove(ObjectContainer container) {
		// Simple request, once it's sent, it's sent. May be requeued at a different # retries.
		return true;
	}

	public void onGotKey(Key key, KeyBlock block, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(this, 1);
			container.activate(key, 5);
			container.activate(this.key, 5);
		}
		synchronized(this) {
			chosen = true;
			if(finished) {
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "onGotKey() called twice on "+this, new Exception("debug"));
				return;
			}
			finished = true;
			if(persistent)
				container.store(this);
			if(isCancelled(container)) return;
			if(key == null)
				throw new NullPointerException();
			if(this.key == null)
				throw new NullPointerException("Key is null on "+this);
			if(!key.equals(this.key.getNodeKey())) {
				Logger.normal(this, "Got sent key "+key+" but want "+this.key+" for "+this);
				return;
			}
		}
		unregister(container, context); // Key has already been removed from pendingKeys
		try {
			onSuccess(Key.createKeyBlock(this.key, block), false, null, container, context);
		} catch (KeyVerifyException e) {
			Logger.error(this, "onGotKey("+key+","+block+") got "+e+" for "+this, e);
			// FIXME if we get rid of the direct route this must call onFailure()
		}
		if(persistent) {
			container.deactivate(this, 1);
			container.deactivate(this.key, 1);
		}
	}
	

	@Override
	public long getCooldownWakeup(Object token, ObjectContainer container) {
		return cooldownWakeupTime;
	}

	@Override
	public long getCooldownWakeupByKey(Key key, ObjectContainer container) {
		return cooldownWakeupTime;
	}
	
	@Override
	public synchronized void resetCooldownTimes(ObjectContainer container) {
		cooldownWakeupTime = -1;
		if(persistent)
			container.store(this);
	}

	@Override
	public void requeueAfterCooldown(Key key, long time, ObjectContainer container, ClientContext context) {
		if(cooldownWakeupTime > time) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Not requeueing as deadline has not passed yet");
			return;
		}
		if(persistent)
			container.activate(this.key, 5);
		if(!(key.equals(this.key.getNodeKey()))) {
			Logger.error(this, "Got requeueAfterCooldown for wrong key: "+key+" but mine is "+this.key.getNodeKey()+" for "+this.key);
			return;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Requeueing after cooldown "+key+" for "+this);
		reschedule(container, context);
		if(persistent)
			container.deactivate(this.key, 5);
	}

	public void schedule(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(ctx, 1);
			if(ctx.blocks != null)
				container.activate(ctx.blocks, 5);
		}
		try {
			getScheduler(context).register(this, new SendableGet[] { this }, persistent, true, container, ctx.blocks, false);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}
	
	public void reschedule(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(ctx, 1);
			if(ctx.blocks != null)
				container.activate(ctx.blocks, 5);
		}
		try {
			getScheduler(context).register(null, new SendableGet[] { this }, persistent, true, container, ctx.blocks, true);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}
	
	public SendableGet getRequest(Key key, ObjectContainer container) {
		return this;
	}

	public Key[] listKeys(ObjectContainer container) {
		if(cancelled || finished)
			return new Key[0];
		else {
			if(persistent)
				container.activate(key, 5);
			return new Key[] { key.getNodeKey() };
		}
	}

	@Override
	public List<PersistentChosenBlock> makeBlocks(PersistentChosenRequest request, RequestScheduler sched, ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(key, 5);
		ClientKey ckey = key.cloneKey();
		PersistentChosenBlock block = new PersistentChosenBlock(false, request, keys[0], ckey.getNodeKey(), ckey, sched);
		return Collections.singletonList(block);
	}

	public KeyListener makeKeyListener(ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(key, 5);
			container.activate(parent, 1);
			container.activate(ctx, 1);
		}
		if(finished) return null;
		KeyListener ret = new SingleKeyListener(key.getNodeKey().cloneKey(), this, !ctx.cacheLocalRequests, parent.getPriorityClass(), persistent);
		if(persistent) {
			container.deactivate(key, 5);
			container.deactivate(parent, 1);
			container.deactivate(ctx, 1);
		}
		return ret;
	}
	
}
