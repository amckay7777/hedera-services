package com.hedera.services.grpc.marshalling;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.state.submerkle.CustomFee;
import com.hedera.services.state.submerkle.CustomFeesBalanceChange;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import javafx.util.Pair;

import java.util.ArrayList;
import java.util.List;

import static com.hedera.services.ledger.BalanceChange.hbarAdjust;
import static com.hedera.services.ledger.BalanceChange.tokenAdjust;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

/**
 * Contains the logic to translate from a gRPC CryptoTransfer operation
 * to a validated list of balance changes, both ℏ and token unit.
 *
 * Once custom fees are implemented for HIP-18, this translation will
 * become somewhat more complicated, since it will need to analyze the
 * token transfers for any custom fee payments that need to be made.
 *
 * (C.f. https://github.com/hashgraph/hedera-services/issues/1587)
 */
public class ImpliedTransfersMarshal {
	private final GlobalDynamicProperties dynamicProperties;
	private final PureTransferSemanticChecks transferSemanticChecks;
	private final CustomFeeSchedules customFeeSchedules;

	public ImpliedTransfersMarshal(
			GlobalDynamicProperties dynamicProperties,
			PureTransferSemanticChecks transferSemanticChecks,
			CustomFeeSchedules customFeeSchedules
	) {
		this.dynamicProperties = dynamicProperties;
		this.transferSemanticChecks = transferSemanticChecks;
		this.customFeeSchedules = customFeeSchedules;
	}

	public ImpliedTransfers unmarshalFromGrpc(CryptoTransferTransactionBody op, AccountID payer) {
		final var maxTokenAdjusts = dynamicProperties.maxTokenTransferListSize();
		final var maxHbarAdjusts = dynamicProperties.maxTransferListSize();

		final var validity = transferSemanticChecks.fullPureValidation(
				maxHbarAdjusts, maxTokenAdjusts, op.getTransfers(), op.getTokenTransfersList());
		if (validity != OK) {
			return ImpliedTransfers.invalid(maxHbarAdjusts, maxTokenAdjusts, validity);
		}

		final List<BalanceChange> changes = new ArrayList<>();
		final List<Pair<EntityId, List<CustomFee>>> customFeesChanges = new ArrayList<>();
		List<CustomFeesBalanceChange> customFeeBalanceChangesForRecord = new ArrayList<>();
		for (var aa : op.getTransfers().getAccountAmountsList()) {
			changes.add(hbarAdjust(aa));
		}
		var payerId = EntityId.fromGrpcAccountId(payer);
		for (var scopedTransfers : op.getTokenTransfersList()) {
			final var grpcTokenId = scopedTransfers.getToken();
			final var scopingToken = EntityId.fromGrpcTokenId(grpcTokenId);
			var amount = 0L;
			for (var aa : scopedTransfers.getTransfersList()) {
				changes.add(tokenAdjust(scopingToken, grpcTokenId, aa));
				if (aa.getAmount() > 0) {
					amount += aa.getAmount();
				}
			}

			List<CustomFee> customFeesOfToken = customFeeSchedules.lookupScheduleFor(scopingToken);
			customFeesChanges.add(new Pair<>(scopingToken, customFeesOfToken));
			List<BalanceChange> customFeeChanges = computeBalanceChangeForCustomFee(scopingToken, payerId, amount,
					customFeesOfToken);
			changes.addAll(customFeeChanges);
			customFeeBalanceChangesForRecord.addAll(getListOfBalanceChangesForCustomFees(customFeeChanges));
		}
		return ImpliedTransfers.valid(maxHbarAdjusts, maxTokenAdjusts, changes, customFeesChanges,
				customFeeBalanceChangesForRecord);
	}

	/**
	 * Compute the balance changes for custom fees to be added to all balance changes in transfer list
	 * @param scopingToken token Id that is being transferred
	 * @param payerId payer Id for the transaction
	 * @param totalAmount total amount being transferred in transfer transaction
	 * @param customFeesOfToken list of custom fees for the token
	 * @return
	 */
	private List<BalanceChange> computeBalanceChangeForCustomFee(EntityId scopingToken, EntityId payerId,
			long totalAmount, List<CustomFee> customFeesOfToken) {
		List<BalanceChange> customFeeChanges = new ArrayList<>();
		for (CustomFee fees : customFeesOfToken) {
			if (fees.getFeeType() == CustomFee.FeeType.FIXED_FEE) {
				customFeeChanges.addAll(getFixedFeeBalanceChanges(fees, payerId));
			} else if (fees.getFeeType() == CustomFee.FeeType.FRACTIONAL_FEE) {
				customFeeChanges.addAll(getFractionalFeeBalanceChanges(fees, payerId, totalAmount, scopingToken));
			}
		}
		return customFeeChanges;
	}

	/**
	 * Calculate fractional fee balance changes for the custom fees
	 * @param fees custom fees
	 * @param payerId payer id for the transaction
	 * @param totalAmount total hbar/token amount that is transferred
	 * @param scopingToken tokenId that is being transferred
	 * @return
	 */
	private List<BalanceChange> getFractionalFeeBalanceChanges(CustomFee fees,
			EntityId payerId, long totalAmount, EntityId scopingToken) {
		List<BalanceChange> fractionalFeeBalanceChanges = new ArrayList<>();
		long fee =
				(fees.getFractionalFeeSpec().getNumerator() * totalAmount / fees.getFractionalFeeSpec().getDenominator());
		long feesToCollect = Math.max(fee, fees.getFractionalFeeSpec().getMinimumUnitsToCollect());

		if (fees.getFractionalFeeSpec().getMaximumUnitsToCollect() > 0) {
			feesToCollect = Math.min(feesToCollect, fees.getFractionalFeeSpec().getMaximumUnitsToCollect());
		}
		fractionalFeeBalanceChanges.add(tokenAdjust(fees.getFeeCollector(), scopingToken, feesToCollect));
		fractionalFeeBalanceChanges.add(tokenAdjust(payerId, scopingToken, -feesToCollect));
		return fractionalFeeBalanceChanges;
	}

	/**
	 * Calculate Fixed fee balance changes for the custom fees
	 * @param fees custom fees
	 * @param payerId payer id for the transaction
	 * @return
	 */
	private List<BalanceChange> getFixedFeeBalanceChanges(CustomFee fees, EntityId payerId) {
		List<BalanceChange> fixedFeeBalanceChanges = new ArrayList<>();
		if (fees.getFixedFeeSpec().getTokenDenomination() == null) {
			fixedFeeBalanceChanges.add(hbarAdjust(fees.getFeeCollector(),
					fees.getFixedFeeSpec().getUnitsToCollect()));
			fixedFeeBalanceChanges.add(hbarAdjust(payerId, -fees.getFixedFeeSpec().getUnitsToCollect()));
		} else {
			fixedFeeBalanceChanges.add(tokenAdjust(fees.getFeeCollector(),
					fees.getFixedFeeSpec().getTokenDenomination(),
					fees.getFixedFeeSpec().getUnitsToCollect()));
			fixedFeeBalanceChanges.add(tokenAdjust(payerId,
					fees.getFixedFeeSpec().getTokenDenomination(),
					-fees.getFixedFeeSpec().getUnitsToCollect()));
		}
		return fixedFeeBalanceChanges;
	}

	/**
	 * Get list of {@link CustomFeesBalanceChange} to be set for
	 * {@link com.hedera.services.state.submerkle.ExpirableTxnRecord} from list of all balance changes in transfer list
	 *
	 * @param customFeeChanges
	 * 		custom fees balance changes
	 * @return
	 */
	private List<CustomFeesBalanceChange> getListOfBalanceChangesForCustomFees(List<BalanceChange> customFeeChanges) {
		List<CustomFeesBalanceChange> balanceChange = new ArrayList<>();
		for (BalanceChange change : customFeeChanges) {
			balanceChange.add(new CustomFeesBalanceChange(
					EntityId.fromGrpcAccountId(change.accountId()),
					change.isForHbar() ? null : EntityId.fromGrpcTokenId(change.tokenId()),
					change.units()));
		}
		return balanceChange;
	}
}
