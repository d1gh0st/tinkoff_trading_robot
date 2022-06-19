package tinkoff_trading_robot.classes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Money;
import ru.tinkoff.piapi.core.models.Position;
import tinkoff_trading_robot.MainScenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ru.tinkoff.piapi.core.utils.MapperUtils.quotationToBigDecimal;

public class ApiMethods {

    static final Logger log = LoggerFactory.getLogger(ApiMethods.class);

    private InvestApi api;

    public ApiMethods(InvestApi investApi)
    {
        api = investApi;
    }

    public BigDecimal getCurrentBuyPrice(String figi)
    {
        log.debug("getCurrentBuyPrice");
        var depth = 10;
        var orderBook = api.getMarketDataService().getOrderBookSync(figi, depth);
        var bids = orderBook.getBidsList();
        BigDecimal price;
        if(bids.size() > 0)
            price = quotationToBigDecimal(bids.get(0).getPrice());
        else price = null;
        return price;
    }

    public boolean CheckAvailableMoney(String currency, double sharePrice)
    {
        log.debug("CheckAvailableMoney");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();

        var positions = api.getOperationsService().getPositionsSync(mainAccount);


        var moneyList = positions.getMoney();
        for (Money moneyValue : moneyList) {
            if(currency.equals(moneyValue.getCurrency().getCurrencyCode())) {
                log.info("currency: {}, value: {}", moneyValue.getCurrency(), moneyValue.getValue());
                double balance = moneyValue.getValue().doubleValue();
                balance /= 3;
                if(balance > sharePrice)
                {
                    return true;
                }
                else return false;
            }
        }

        return false;
    }

    public void BuyShareByMarketPrice(String figi)
    {
        log.debug("BuyShareByMarketPrice");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();

        var lastPrice = api.getMarketDataService().getLastPricesSync(List.of(figi)).get(0).getPrice();
        var minPriceIncrement = api.getInstrumentsService().getInstrumentByFigiSync(figi).getMinPriceIncrement();
        var price = Quotation.newBuilder().setUnits(lastPrice.getUnits() - minPriceIncrement.getUnits())
                .setNano(lastPrice.getNano() - minPriceIncrement.getNano()).build();

        log.info("Last price - " + lastPrice.getUnits() + "," + lastPrice.getNano());
        log.info("Buy price -  " + price.getUnits() + "," + price.getNano());

        var orderId = api.getOrdersService()
                .postOrderSync(figi, 1, price, OrderDirection.ORDER_DIRECTION_BUY, mainAccount, OrderType.ORDER_TYPE_MARKET,
                        UUID.randomUUID().toString()).getOrderId();
        log.info("Buy order id: {}",orderId);
    }

    public Double GetAverageMonthPrice(String figi)
    {
        log.debug("GetAverageMonthPrice");
        var candlesDay = api.getMarketDataService()
                .getCandlesSync(figi, Instant.now().minus(30, ChronoUnit.DAYS), Instant.now(), CandleInterval.CANDLE_INTERVAL_DAY);
        log.info("got {} 1-day candles for instrument with figi {}", candlesDay.size(), figi);
        double averageClosePrice = 0;
        for (HistoricCandle candle : candlesDay) {
            averageClosePrice += quotationToBigDecimal(candle.getClose()).doubleValue();
        }
        averageClosePrice /= candlesDay.size();
        return averageClosePrice;
    }

    public List<Position> GetShares()
    {
        log.debug("GetShares");
        List<Position> result = new ArrayList<>();
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();
        var portfolio = api.getOperationsService().getPortfolioSync(mainAccount);
        var positions = portfolio.getPositions();
        //log.info("amount {} positions", positions.size());
        for (int i = 0; i < positions.size(); i++) {
            var position = positions.get(i);
            result.add(position);
        }
        return result;
    }

    public void CancelStopOrders(String figi)
    {
        log.debug("CancelStopOrders");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();
        var stopOrders = api.getStopOrdersService().getStopOrdersSync(mainAccount);
        for(StopOrder order : stopOrders)
        {
            if(order.getFigi().equals(figi))
            {
                api.getStopOrdersService().cancelStopOrder(mainAccount, order.getStopOrderId());
                break;
            }
        }
    }

    public BigDecimal getStopOrderPrice(String figi)
    {
        log.debug("getStopOrderPrice");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();
        var stopOrders = api.getStopOrdersService().getStopOrdersSync(mainAccount);
        for(StopOrder order : stopOrders)
        {
            if(order.getFigi().equals(figi))
            {
                MoneyValue moneyValue = order.getStopPrice();
                BigDecimal price = moneyValue.getUnits() == 0 && moneyValue.getNano() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(moneyValue.getUnits()).add(BigDecimal.valueOf(moneyValue.getNano(), 9));
                return price;
            }
        }
        return null;
    }

    public boolean CheckStopOrder(String figi)
    {
        log.debug("CheckStopOrder");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();
        var stopOrders = api.getStopOrdersService().getStopOrdersSync(mainAccount);
        for(StopOrder order : stopOrders)
        {
            if(order.getFigi().equals(figi))
            {

                return true;
            }
        }
        return false;
    }

    /*private MoneyValue GetCurrentPrice(Share share)
    {
        return null;
    }*/

    public void SetupStopMarket(InvestApi api, String figi, BigDecimal price, long quantity)
    {
        log.debug("SetupStopMarket");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();
        BigDecimal new_price = BigDecimal.valueOf(0);
        var minPriceIncrement = api.getInstrumentsService().getInstrumentByFigiSync(figi).getMinPriceIncrement();
        BigDecimal bigDecimal = minPriceIncrement.getUnits() == 0 && minPriceIncrement.getNano() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(minPriceIncrement.getUnits()).add(BigDecimal.valueOf(minPriceIncrement.getNano(), 9));
        while (new_price.doubleValue() < price.doubleValue()) new_price = new_price.add(bigDecimal);
        price = new_price;
        long units = price.longValue();
        BigDecimal modulo = price.subtract(BigDecimal.valueOf(units));
        int nanos = (modulo.multiply(BigDecimal.valueOf(1000000000))).intValue();
        var stopPrice = Quotation.newBuilder().setUnits(units)
                .setNano(nanos).build();
        log.info("Quantity: {}", quantity);
        var stopOrderId = api.getStopOrdersService()
                .postStopOrderGoodTillDateSync(figi, 1, stopPrice, stopPrice, StopOrderDirection.STOP_ORDER_DIRECTION_SELL,
                        mainAccount, StopOrderType.STOP_ORDER_TYPE_STOP_LOSS, Instant.now().plus(30, ChronoUnit.DAYS));
        log.info("Stop-order id: {}", stopOrderId);
    }

    public BigDecimal getDividentAmount(String figi)
    {
        log.debug("getDividentAmount");
        BigDecimal amount = null;
        Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(figi);
        if(ins.getInstrumentType().equals("share")) {
            var dividends =
                    api.getInstrumentsService().getDividendsSync(figi, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
            MoneyValue moneyValue = dividends.get(0).getDividendNet();
            amount = moneyValue.getUnits() == 0 && moneyValue.getNano() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(moneyValue.getUnits()).add(BigDecimal.valueOf(moneyValue.getNano(), 9));
            amount = amount.multiply(BigDecimal.valueOf(ins.getLot()));
        }
        return amount;
    }

    public boolean CheckDividendDate(String figi)
    {
        log.debug("CheckDividendDate");
        Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(figi);
        if(ins.getInstrumentType().equals("share")) {
            var dividends =
                    api.getInstrumentsService().getDividendsSync(figi, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
            for (Dividend dividend : dividends) {
                log.info("Dividends for ticker: {}. Amount: {}, Currency: {}, Date: {}", ins.getTicker(),
                        dividend.getDividendNet().getUnits() + "," + dividend.getDividendNet().getNano(),
                        dividend.getDividendNet().getCurrency(), dividend.getRecordDate().getSeconds());
                return true;
            }
        }
        return false;
    }
}
