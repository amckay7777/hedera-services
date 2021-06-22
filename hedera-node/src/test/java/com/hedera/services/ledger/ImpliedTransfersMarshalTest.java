package com.hedera.services.ledger;

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
import com.hedera.services.grpc.marshalling.ImpliedTransfers;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMeta;
import com.hedera.services.state.submerkle.CustomFee;
import com.hedera.services.state.submerkle.CustomFeesBalanceChange;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.txns.CustomFeeSchedules;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import javafx.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.hedera.test.utils.IdUtils.adjustFrom;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asToken;
import static com.hedera.test.utils.IdUtils.hbarChange;
import static com.hedera.test.utils.IdUtils.tokenChange;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ImpliedTransfersMarshalTest {
	private CryptoTransferTransactionBody op;

	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private PureTransferSemanticChecks transferSemanticChecks;

	@Mock
	private CustomFeeSchedules customFeeSchedules;

	private ImpliedTransfersMarshal subject;

	private final AccountID aModel = asAccount("1.2.3");
	private final AccountID bModel = asAccount("2.3.4");
	private final AccountID cModel = asAccount("3.4.5");
	private final AccountID payer = asAccount("5.6.7");
	private final EntityId token = new EntityId(0, 0, 75231);
	private final EntityId anotherToken = new EntityId(0, 0, 75232);
	private final EntityId yetAnotherToken = new EntityId(0, 0, 75233);
	private final TokenID anId = asToken("0.0.75231");
	private final TokenID anotherId = asToken("0.0.75232");
	private final TokenID yetAnotherId = asToken("0.0.75233");
	private final AccountID a = asAccount("1.2.3");
	private final AccountID b = asAccount("2.3.4");
	private final AccountID c = asAccount("3.4.5");

	private final long aHbarChange = -100L;
	private final long bHbarChange = +50L;
	private final long cHbarChange = +50L;
	private final long aAnotherTokenChange = -50L;
	private final long bAnotherTokenChange = +25L;
	private final long cAnotherTokenChange = +25L;
	private final long bTokenChange = -100L;
	private final long cTokenChange = +100L;
	private final long aYetAnotherTokenChange = -15L;
	private final long bYetAnotherTokenChange = +15L;
	private final long customFeeChangeToFeeCollector = +20L;
	private final long customFeeChangeFromPayer = -20L;

	private final int maxExplicitHbarAdjusts = 5;
	private final int maxExplicitTokenAdjusts = 50;

	private final EntityId customFeeToken = new EntityId(0, 0, 123);
	private final EntityId customFeeCollector = new EntityId(0, 0, 124);
	final List<Pair<EntityId, List<CustomFee>>> customFeesChanges = List.of(
			new Pair<>(customFeeToken, new ArrayList<>()));
	private final List<CustomFeesBalanceChange> customFeeBalanceChanges = List.of(
			new CustomFeesBalanceChange(customFeeCollector, customFeeToken, 123L));

	private final long numerator = 2L;
	private final long denominator = 100L;
	private final long minimumUnitsToCollect = 20L;
	private final long maximumUnitsToCollect = 100L;
	EntityId feeCollector = EntityId.fromGrpcAccountId(aModel);

	@BeforeEach
	void setUp() {
		subject = new ImpliedTransfersMarshal(dynamicProperties, transferSemanticChecks, customFeeSchedules);
	}

	@Test
	void validatesXfers() {
		setupFixtureOp();
		final var expectedMeta = new ImpliedTransfersMeta(
				maxExplicitHbarAdjusts, maxExplicitTokenAdjusts, TRANSFER_LIST_SIZE_LIMIT_EXCEEDED,
				Collections.emptyList());

		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		// and:
		given(transferSemanticChecks.fullPureValidation(
				maxExplicitHbarAdjusts,
				maxExplicitTokenAdjusts,
				op.getTransfers(),
				op.getTokenTransfersList())).willReturn(TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);

		// when:
		final var result = subject.unmarshalFromGrpc(op, bModel);

		// then:
		assertEquals(result.getMeta(), expectedMeta);
	}

	@Test
	void getsExpectedList() {
		setupFixtureOp();
		// and:
		final List<BalanceChange> expectedChanges = List.of(new BalanceChange[] {
						hbarChange(aModel, aHbarChange),
						hbarChange(bModel, bHbarChange),
						hbarChange(cModel, cHbarChange),
						tokenChange(anotherToken, aModel, aAnotherTokenChange),
						tokenChange(anotherToken, bModel, bAnotherTokenChange),
						tokenChange(anotherToken, cModel, cAnotherTokenChange),
						hbarChange(aModel, customFeeChangeToFeeCollector),
						hbarChange(payer, customFeeChangeFromPayer),
						tokenChange(token, bModel, bTokenChange),
						tokenChange(token, cModel, cTokenChange),
						hbarChange(aModel, customFeeChangeToFeeCollector),
						hbarChange(payer, customFeeChangeFromPayer),
						tokenChange(yetAnotherToken, aModel, aYetAnotherTokenChange),
						tokenChange(yetAnotherToken, bModel, bYetAnotherTokenChange),
						hbarChange(aModel, customFeeChangeToFeeCollector),
						hbarChange(payer, customFeeChangeFromPayer)
				}
		);
		final List<CustomFee> customFee = getFixedCustomFee();
		final List<Pair<EntityId, List<CustomFee>>> expectedCustomFeeChanges =
				List.of(new Pair<>(anotherToken, customFee),
						new Pair<>(token, customFee),
						new Pair<>(yetAnotherToken, customFee));
		final List<CustomFeesBalanceChange> expectedCustomFeeBalanceChanges = List.of(
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(aModel), customFeeChangeToFeeCollector),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(payer), customFeeChangeFromPayer),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(aModel), customFeeChangeToFeeCollector),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(payer), customFeeChangeFromPayer),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(aModel), customFeeChangeToFeeCollector),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(payer), customFeeChangeFromPayer));

		// and:
		final var expectedMeta = new ImpliedTransfersMeta(maxExplicitHbarAdjusts, maxExplicitTokenAdjusts,
				OK, expectedCustomFeeChanges);

		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		given(transferSemanticChecks.fullPureValidation(
				maxExplicitHbarAdjusts,
				maxExplicitTokenAdjusts,
				op.getTransfers(),
				op.getTokenTransfersList())).willReturn(OK);
		given(customFeeSchedules.lookupScheduleFor(any())).willReturn(customFee);

		// when:
		final var result = subject.unmarshalFromGrpc(op, payer);

		// then:
		assertEquals(expectedMeta, result.getMeta());
		assertEquals(expectedChanges, result.getAllBalanceChanges());
		assertEquals(expectedCustomFeeChanges, result.getCustomFeesChanges());
		assertEquals(expectedCustomFeeBalanceChanges, result.getCustomFeesBalanceChanges());
	}

	@Test
	void metaObjectContractSanityChecks() {
		// given:
		final var oneMeta = new ImpliedTransfersMeta(3, 4, OK, customFeesChanges);
		final var twoMeta = new ImpliedTransfersMeta(1, 2, TOKEN_WAS_DELETED, Collections.emptyList());
		// and:
		final var oneRepr = "ImpliedTransfersMeta{code=OK, " +
				"maxExplicitHbarAdjusts=3, maxExplicitTokenAdjusts=4, " +
				"customFeeSchedulesUsedInMarshal=[EntityId{shard=0, realm=0, num=123}=[]]}";
		final var twoRepr = "ImpliedTransfersMeta{code=TOKEN_WAS_DELETED, " +
				"maxExplicitHbarAdjusts=1, maxExplicitTokenAdjusts=2, customFeeSchedulesUsedInMarshal=[]}";

		// expect:
		assertNotEquals(oneMeta, twoMeta);
		assertNotEquals(oneMeta.hashCode(), twoMeta.hashCode());
		// and:
		assertEquals(oneRepr, oneMeta.toString());
		assertEquals(twoRepr, twoMeta.toString());
	}

	@Test
	void impliedXfersObjectContractSanityChecks() {
		// given:
		final var twoChanges = List.of(tokenChange(
				new EntityId(1, 2, 3),
				asAccount("4.5.6"),
				7));
		final var oneImpliedXfers = ImpliedTransfers.invalid(3, 4, TOKEN_WAS_DELETED);
		final var twoImpliedXfers = ImpliedTransfers.valid(1, 100, twoChanges,
				customFeesChanges, customFeeBalanceChanges);
		// and:
		final var oneRepr = "ImpliedTransfers{meta=ImpliedTransfersMeta{code=TOKEN_WAS_DELETED, " +
				"maxExplicitHbarAdjusts=3, maxExplicitTokenAdjusts=4, customFeeSchedulesUsedInMarshal=[]}, changes=[], " +
				"customFeesChanges=[]}";
		final var twoRepr = "ImpliedTransfers{meta=ImpliedTransfersMeta{code=OK, maxExplicitHbarAdjusts=1, " +
				"maxExplicitTokenAdjusts=100, customFeeSchedulesUsedInMarshal=[EntityId{shard=0, realm=0, " +
				"num=123}=[]]}, " +
				"changes=[BalanceChange{token=EntityId{shard=1, realm=2, num=3}, account=EntityId{shard=4, realm=5, " +
				"num=6}, " +
				"units=7}], customFeesChanges=[EntityId{shard=0, realm=0, num=123}=[]]}";

		// expect:
		assertNotEquals(oneImpliedXfers, twoImpliedXfers);
		assertNotEquals(oneImpliedXfers.hashCode(), twoImpliedXfers.hashCode());
		// and:
		assertEquals(oneRepr, oneImpliedXfers.toString());
		assertEquals(twoRepr, twoImpliedXfers.toString());
	}

	@Test
	void metaRecognizesIdenticalConditions() {
		// given:
		final var meta = new ImpliedTransfersMeta(3, 4, OK, customFeesChanges);

		given(dynamicProperties.maxTransferListSize()).willReturn(3);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(4);

		// expect:
		assertTrue(meta.wasDerivedFrom(dynamicProperties, customFeesChanges));

		//modify customFeeChanges to see test fails
		final var modifiedList = new ArrayList<>(customFeesChanges);
		modifiedList.add(new Pair<>(token, Collections.emptyList()));
		assertFalse(meta.wasDerivedFrom(dynamicProperties, modifiedList));

		// and:
		given(dynamicProperties.maxTransferListSize()).willReturn(2);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(4);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, Collections.emptyList()));

		// and:
		given(dynamicProperties.maxTransferListSize()).willReturn(3);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(3);

		// expect:
		assertFalse(meta.wasDerivedFrom(dynamicProperties, customFeesChanges));
	}

	@Test
	void getsExpectedListWithFractionalCustomFee() {
		op = CryptoTransferTransactionBody.newBuilder()
				.addTokenTransfers(TokenTransferList.newBuilder()
						.setToken(anotherId)
						.addAllTransfers(List.of(
								adjustFrom(a, cHbarChange)
						))).build();
		// and:
		final var expectedFractionalFee = Math.min(
				Math.max(numerator * cHbarChange / denominator, minimumUnitsToCollect), maximumUnitsToCollect);
		final var expectedChanges = List.of(new BalanceChange[] {
				tokenChange(anotherToken, aModel, cHbarChange),
				tokenChange(anotherToken, aModel, expectedFractionalFee),
				tokenChange(anotherToken, payer, -expectedFractionalFee) });

		final var customFee = getFractionalCustomFee();
		final var expectedCustomFeeChanges =
				List.of(new Pair<>(anotherToken, customFee));
		final var expectedCustomFeeBalanceChanges = List.of(
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(aModel), anotherToken, expectedFractionalFee),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(payer), anotherToken, -expectedFractionalFee));

		// and:
		final var expectedMeta = new ImpliedTransfersMeta(maxExplicitHbarAdjusts, maxExplicitTokenAdjusts,
				OK, expectedCustomFeeChanges);

		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		given(transferSemanticChecks.fullPureValidation(
				maxExplicitHbarAdjusts,
				maxExplicitTokenAdjusts,
				op.getTransfers(),
				op.getTokenTransfersList())).willReturn(OK);
		given(customFeeSchedules.lookupScheduleFor(any())).willReturn(customFee);

		// when:
		final var result = subject.unmarshalFromGrpc(op, payer);

		// then:
		assertEquals(expectedMeta, result.getMeta());
		assertEquals(expectedChanges, result.getAllBalanceChanges());
		assertEquals(expectedCustomFeeChanges, result.getCustomFeesChanges());
		assertEquals(expectedCustomFeeBalanceChanges, result.getCustomFeesBalanceChanges());
	}

	@Test
	void getsExpectedListWithFixedCustomFeeNonNullDenomination() {
		op = CryptoTransferTransactionBody.newBuilder()
				.addTokenTransfers(TokenTransferList.newBuilder()
						.setToken(anotherId)
						.addAllTransfers(List.of(
								adjustFrom(a, cHbarChange)
						))).build();
		// and:
		final var expectedChanges = List.of(new BalanceChange[] {
				tokenChange(anotherToken, aModel, cHbarChange),
				tokenChange(token, aModel, 20L),
				tokenChange(token, payer, -20L) });

		final var customFee = getFixedCustomFeeNonNullDenom();
		final var expectedCustomFeeChanges =
				List.of(new Pair<>(anotherToken, customFee));
		final var expectedCustomFeeBalanceChanges = List.of(
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(aModel), token, 20L),
				new CustomFeesBalanceChange(EntityId.fromGrpcAccountId(payer), token, -20L));

		// and:
		final var expectedMeta = new ImpliedTransfersMeta(maxExplicitHbarAdjusts, maxExplicitTokenAdjusts,
				OK, expectedCustomFeeChanges);

		given(dynamicProperties.maxTransferListSize()).willReturn(maxExplicitHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxExplicitTokenAdjusts);
		given(transferSemanticChecks.fullPureValidation(
				maxExplicitHbarAdjusts,
				maxExplicitTokenAdjusts,
				op.getTransfers(),
				op.getTokenTransfersList())).willReturn(OK);
		given(customFeeSchedules.lookupScheduleFor(anotherToken)).willReturn(customFee);

		// when:
		final var result = subject.unmarshalFromGrpc(op, payer);

		// then:
		assertEquals(expectedMeta, result.getMeta());
		assertEquals(expectedChanges, result.getAllBalanceChanges());
		assertEquals(expectedCustomFeeChanges, result.getCustomFeesChanges());
		assertEquals(expectedCustomFeeBalanceChanges, result.getCustomFeesBalanceChanges());
	}

	private void setupFixtureOp() {
		var hbarAdjusts = TransferList.newBuilder()
				.addAccountAmounts(adjustFrom(a, -100))
				.addAccountAmounts(adjustFrom(b, 50))
				.addAccountAmounts(adjustFrom(c, 50))
				.build();
		op = CryptoTransferTransactionBody.newBuilder()
				.setTransfers(hbarAdjusts)
				.addTokenTransfers(TokenTransferList.newBuilder()
						.setToken(anotherId)
						.addAllTransfers(List.of(
								adjustFrom(a, -50),
								adjustFrom(b, 25),
								adjustFrom(c, 25)
						)))
				.addTokenTransfers(TokenTransferList.newBuilder()
						.setToken(anId)
						.addAllTransfers(List.of(
								adjustFrom(b, -100),
								adjustFrom(c, 100)
						)))
				.addTokenTransfers(TokenTransferList.newBuilder()
						.setToken(yetAnotherId)
						.addAllTransfers(List.of(
								adjustFrom(a, -15),
								adjustFrom(b, 15)
						)))
				.build();
	}

	private List<CustomFee> getFixedCustomFee() {
		return List.of(
				CustomFee.fixedFee(20L, null, feeCollector)
		);
	}

	private List<CustomFee> getFixedCustomFeeNonNullDenom() {
		return List.of(
				CustomFee.fixedFee(20L, token, feeCollector)
		);
	}

	private List<CustomFee> getFractionalCustomFee() {
		return List.of(
				CustomFee.fractionalFee(numerator, denominator, minimumUnitsToCollect, maximumUnitsToCollect,
						feeCollector)
		);
	}
}
