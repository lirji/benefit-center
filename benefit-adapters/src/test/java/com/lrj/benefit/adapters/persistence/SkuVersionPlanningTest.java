package com.lrj.benefit.adapters.persistence;

import com.lrj.benefit.application.command.AwardIntentCommand;
import com.lrj.benefit.application.port.out.*;
import com.lrj.benefit.application.service.*;
import com.lrj.benefit.contract.*;
import com.lrj.benefit.domain.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkuVersionPlanningTest {
    @Test void versionRejectsNonIntegerJsonWithoutChangingLegacyFields() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String value : List.of("0.9", "0.0", "\"0\"", "true", "[]", "{}", "9223372036854775808")) {
            String body = "{\"clientItemId\":\"i\",\"benefitSkuId\":\"sku\",\"benefitType\":\"COUPON\",\"quantity\":1,\"expectedSkuVersion\":" + value + "}";
            assertThatThrownBy(() -> json.readValue(body, AwardItemIntent.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }
    @Test void legacyWireDoesNotEmitNewNullProperty() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy = item("i", "sku", null);
        assertThat(json.readTree(json.writeValueAsString(legacy)).has("expectedSkuVersion")).isFalse();
        String explicitNull = "{\"clientItemId\":\"i\",\"benefitSkuId\":\"sku\",\"benefitType\":\"COUPON\",\"quantity\":1,\"expectedSkuVersion\":null}";
        assertThat(json.readValue(explicitNull, AwardItemIntent.class).expectedSkuVersion()).isNull();
        assertThat(json.readTree(json.writeValueAsString(item("i", "sku", 0L))).get("expectedSkuVersion").longValue()).isZero();
    }
    final AwardRepository awards=mock(AwardRepository.class);
    final BenefitCatalogRepository catalog=mock(BenefitCatalogRepository.class);
    final SkuTemplateCache cache=mock(SkuTemplateCache.class);
    final SkuAcceptanceRepository locks=mock(SkuAcceptanceRepository.class);
    final InventoryRepository inventory=mock(InventoryRepository.class);
    final UserLimitRepository limits=mock(UserLimitRepository.class);
    final OutboxRepository outbox=mock(OutboxRepository.class);
    final OperationRepository operations=mock(OperationRepository.class);
    final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2026-09-08T00:00:00Z"));
    final Clock clock=new Clock() {
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time.get();}
    };
    AwardApplicationService service() {
        UnitOfWork uow=new UnitOfWork(){public <T>T required(Supplier<T> work){return work.get();}};
        return new AwardApplicationService(awards,catalog,cache,inventory,limits,mock(UserLimitPrecheck.class),
                operations,outbox,uow,prefix->prefix+UUID.randomUUID(),clock,locks);
    }
    static AwardItemIntent item(String id,String sku,Long version){return new AwardItemIntent(id,sku,BenefitType.COUPON,null,null,1,Map.of(),version);}
    static AwardIntent intent(AwardItemIntent...items){return new AwardIntent("1.0","marketing-referral","request",null,"subject",null,PartialPolicy.BEST_EFFORT,List.of(items),Map.of());}
    void accept(AwardIntent intent){service().accept(new AwardIntentCommand("T","request",null,"cell",intent));}
    BenefitSku sku(String id,long version){return new BenefitSku("T",id,BenefitType.COUPON,null,null,SkuTemplateStatus.ACTIVE,
            ValidityType.ABSOLUTE,time.get().minusSeconds(10),time.get().plusSeconds(10),null,List.of(),null,null,null,null,version);}
    void route(String sku){when(catalog.routes("T",sku)).thenReturn(List.of(new ChannelRoute("route",sku,1,"CENTER_COUPON",InventoryOwnerType.CENTER_QUOTA,
            null,InventoryReserveMode.LAZY,true,new AdapterCapabilities(true,true,true,true))));}
    void expect(BenefitErrorCode code,Runnable work){assertThatThrownBy(work::run).isInstanceOfSatisfying(BenefitApplicationException.class,e->assertThat(e.code()).isEqualTo(code));}

    @Test void conflictingVersionNeverTouchesCacheOrReserves(){
        when(locks.lockCurrent("T","sku")).thenReturn(Optional.of(sku("sku",2)));
        expect(BenefitErrorCode.SKU_VERSION_CONFLICT,()->accept(intent(item("i","sku",1L))));
        verifyNoInteractions(cache,inventory,limits,outbox);verify(awards,never()).insert(any());
    }
    @Test void duplicateSkuConflictingExpectationsRejectBeforeLocks(){
        expect(BenefitErrorCode.SKU_VERSION_CONFLICT,()->accept(intent(item("i","sku",1L),item("j","sku",2L))));
        verifyNoInteractions(locks,cache,inventory,limits);
    }
    @Test void lockOrderIsSortedAndTimeRecheckedAfterLastRead(){
        var a=sku("a",1);var b=sku("b",1);
        when(locks.lockCurrent("T","a")).thenReturn(Optional.of(a));
        when(locks.lockCurrent("T","b")).thenAnswer(call->{time.set(time.get().plusSeconds(11));return Optional.of(b);});
        expect(BenefitErrorCode.SKU_NOT_ACTIVE,()->accept(intent(item("i","b",1L),item("j","a",1L))));
        var order=inOrder(locks);order.verify(locks).lockCurrent("T","a");order.verify(locks).lockCurrent("T","b");
        verifyNoInteractions(cache,inventory,limits);
    }
    @Test void finalRouteDelayCannotExtendValidity(){
        when(locks.lockCurrent("T","sku")).thenReturn(Optional.of(sku("sku",1)));route("sku");
        var routes=catalog.routes("T","sku");when(catalog.routes("T","sku")).thenAnswer(call->{time.set(time.get().plusSeconds(11));return routes;});
        expect(BenefitErrorCode.SKU_NOT_ACTIVE,()->accept(intent(item("i","sku",1L))));
        verifyNoInteractions(inventory,limits);
    }
    @Test void inventoryWaitCannotExtendFinalAcceptanceValidity(){
        when(locks.lockCurrent("T","sku")).thenReturn(Optional.of(sku("sku",1)));route("sku");
        when(limits.reserve(anyString(),anyString(),anyString(),anyString(),anyLong(),isNull(),isNull(),any())).thenReturn(true);
        when(awards.insert(any())).thenReturn(true);when(awards.updateExpectedVersion(any(),anyLong())).thenReturn(true);
        when(operations.insert(any())).thenReturn(true);
        when(inventory.reserveAvailable(anyString(),anyString(),any(),anyLong(),anyString(),anyString()))
                .thenAnswer(call->{time.set(time.get().plusSeconds(11));return true;});
        expect(BenefitErrorCode.SKU_NOT_ACTIVE,()->accept(intent(item("i","sku",1L))));
        verify(outbox).enqueue(any());
    }
    @Test void legacyMissingVersionStillUsesCache(){
        when(cache.findCurrent("T","sku")).thenReturn(Optional.empty());
        expect(BenefitErrorCode.SKU_NOT_FOUND,()->accept(intent(item("i","sku",null))));
        verify(cache).findCurrent("T","sku");verifyNoInteractions(locks);
    }
    @Test void successfulOriginalReplaysWithoutLookingAtNewVersion(){
        var request=intent(item("i","sku",0L));
        var original=new AwardOrder("T","order","marketing-referral","request",null,"subject",new AwardIntentHasher().hash(request),"cell",
                List.of(new AwardItem("item","i","sku",0,BenefitType.COUPON,1,null,null,AwardItemStatus.PENDING,null,null,null,0)),AwardOrderStatus.ACCEPTED,0);
        when(awards.findBySource("T","marketing-referral","request")).thenReturn(Optional.of(original));
        assertThat(service().accept(new AwardIntentCommand("T","request",null,"cell",request)).replay()).isTrue();
        verifyNoInteractions(locks,cache,inventory,limits);
        expect(BenefitErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,()->accept(intent(item("i","sku",null))));
        expect(BenefitErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,()->accept(intent(item("i","sku",1L))));
    }
    @Test void mapperAdapterForbidsAutocommit(){
        var mapper=mock(SkuAcceptanceMapper.class);
        assertThatThrownBy(()->new MybatisSkuAcceptanceRepository(mapper).lockCurrent("T","sku")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }
}
