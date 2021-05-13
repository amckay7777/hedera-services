package com.hedera.services.ledger.accounts;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.fcmap.FCMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;

public class FCMapBackingAccounts implements BackingStore<AccountID, MerkleAccount> {
	private static final int MAX_ACCOUNTS_LIKELY_TOUCHED_IN_LEDGER_TXN = 12;

	private final Set<AccountID> extantAccounts = new HashSet<>();

	private final List<AccountID> touchedIds = new ArrayList<>(MAX_ACCOUNTS_LIKELY_TOUCHED_IN_LEDGER_TXN);
	private final Map<AccountID, MerkleAccount> cache = new HashMap<>();
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> delegate;

	public FCMapBackingAccounts(Supplier<FCMap<MerkleEntityId, MerkleAccount>> delegate) {
		this.delegate = delegate;
		rebuildFromSources();
	}

	@Override
	public void rebuildFromSources() {
		extantAccounts.clear();
		delegate.get().keySet().stream()
				.map(MerkleEntityId::toAccountId)
				.forEach(extantAccounts::add);
	}

	@Override
	public void flushMutableRefs() {
		final var currentDelegate = delegate.get();
		touchedIds.sort(HederaLedger.ACCOUNT_ID_COMPARATOR);
		for (AccountID id : touchedIds) {
			currentDelegate.replace(fromAccountId(id), cache.get(id));
		}
		cache.clear();
		touchedIds.clear();
	}

	@Override
	public MerkleAccount getRef(AccountID id) {
		return cache.computeIfAbsent(id, ignore -> {
			touchedIds.add(id);
			return delegate.get().getForModify(fromAccountId(id));
		});
	}

	@Override
	public void put(AccountID id, MerkleAccount account) {
		if (!extantAccounts.contains(id)) {
			delegate.get().put(fromAccountId(id), account);
			extantAccounts.add(id);
		}
	}

	@Override
	public boolean contains(AccountID id) {
		return extantAccounts.contains(id);
	}

	@Override
	public void remove(AccountID id) {
		extantAccounts.remove(id);
		delegate.get().remove(fromAccountId(id));
	}

	@Override
	public Set<AccountID> idSet() {
		return extantAccounts;
	}

	@Override
	public MerkleAccount getUnsafeRef(AccountID id) {
		return delegate.get().get(fromAccountId(id));
	}
}
