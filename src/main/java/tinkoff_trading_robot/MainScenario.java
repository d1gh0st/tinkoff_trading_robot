package tinkoff_trading_robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Money;
import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ru.tinkoff.piapi.core.utils.DateUtils.timestampToString;
import static ru.tinkoff.piapi.core.utils.MapperUtils.quotationToBigDecimal;

public class MainScenario {

    static final Logger log = LoggerFactory.getLogger(MainScenario.class);

    private static InvestApi api;

    public static void main(String[] args) {



        String instrumentFigi = "";

        var token = "";
        api = InvestApi.create(token);

        /*var shares = api.getInstrumentsService().getTradableSharesSync();
        for(int i=0;i<shares.size();i++)
        {
            if(shares.get(i).getTicker().equals("SPCE")) instrumentFigi = shares.get(i).getFigi();
        }*/

        //System.out.println(CheckAvailableMoney("USD",3));
        //System.out.println(GetAverageMonthPrice(instrumentFigi));
        //System.out.println(GetProfit(instrumentFigi,api));
        /*List<Position> positions = GetShares();
        for(Position position : positions)
        {

            log.info("ticker: {}, profitability: {}",api.getInstrumentsService().getInstrumentByFigiSync(position.getFigi()).getTicker()
                    ,position.getExpectedYield());
        }*/
        //System.out.println(CheckStopOrder(instrumentFigi));
        //System.out.println(CheckDividendDate(instrumentFigi));
        //SetupStopMarket(api,instrumentFigi,BigDecimal.valueOf(5.1));
        //SecurityTradingStatus status = api.getInstrumentsService().getInstrumentByFigiSync(instrumentFigi).getTradingStatus();
        //System.out.println(status);
        //BuyShareByMarketPrice(instrumentFigi);
        try {
            while (true) {
                log.info("Run CheckSellStrategy1");
                CheckSellStrategy1();
                log.info("=================================================================================================================");
                Thread.sleep(60000);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }


    private static void CheckSellStrategy1()
    {
        List<Position> positions = GetShares();
        for(Position position : positions)
        {
            if(position.getInstrumentType().equals("share")) {
                Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(position.getFigi());
                log.info("Check dividend date for {} ---------------->", ins.getTicker());
                log.debug("Quantity: {}", position.getQuantityLots());
                log.debug("amount of shares in 1 lot: {}",ins.getLot());
                if (!CheckDividendDate(position.getFigi())) {
                    log.info("Dividend date does not exist in the nearest future ---------------->");
                    BigDecimal profit = position.getExpectedYield().divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
                    log.info("profit: {}",profit);
                    if(profit.doubleValue() > 0) {
                        log.info("Profit more then zero ---------------->");
                        log.info("Check stop orders");
                        if(CheckStopOrder(position.getFigi()))
                        {
                            log.info("Stop order exist ---------------->");
                            BigDecimal current_price = getCurrentBuyPrice(position.getFigi());
                            if(current_price == null)
                            {
                                log.info("order-book is unavailable");
                            }
                            else {
                                log.info("Current buy price: {}", current_price);
                                BigDecimal buy_price = getStopOrderPrice(position.getFigi());
                                log.info("Stop order price: {}", buy_price);
                                profit = current_price.subtract(buy_price);
                                log.info("Profit from stop order price: {}", profit);
                                BigDecimal profit_percent = (current_price.multiply(BigDecimal.valueOf(100)).divide(buy_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
                                log.info("Profit percent: {}", profit_percent);
                                if (profit_percent.doubleValue() > 0.5) {
                                    log.info("Profit more than 0.5! ---------------->");
                                    BigDecimal price = buy_price.add(profit.divide(BigDecimal.valueOf(ins.getLot()),2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(ins.getLot()), 2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                                    //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                                    if(price.doubleValue() > buy_price.doubleValue()) {
                                        log.info("Setup new stop order with price: {}", price);
                                        CancelStopOrders(position.getFigi());
                                        SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                                    }
                                    else
                                    {
                                        log.info("Calculated price is less or equal to stop price");
                                    }
                                }
                            }
                        }
                        else {
                            log.info("Stop order does not exist ---------------->");
                            BigDecimal current_price = getCurrentBuyPrice(position.getFigi());
                            if(current_price == null)
                            {
                                log.info("order-book is unavailable");
                            }
                            else {
                                log.info("Current buy price: {}", current_price);
                                //BigDecimal buy_price = current_price.subtract(profit.divide(BigDecimal.valueOf(ins.getLot()),2, RoundingMode.HALF_UP));
                                //BigDecimal buy_price = position.getAveragePositionPrice().getValue();//???rounding??
                                BigDecimal buy_price = position.getAveragePositionPriceFifo().getValue();
                                log.info("Position price: {}", buy_price);
                                BigDecimal profit_percent = (current_price.multiply(BigDecimal.valueOf(100)).divide(buy_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
                                log.info("Profit percent: {}", profit_percent);
                                if (profit_percent.doubleValue() > 0.5) {
                                    log.info("Profit more than 0.5! ---------------->");
                                    BigDecimal price = buy_price.add(profit.divide(BigDecimal.valueOf(ins.getLot()), 2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                                    //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                                    if(price.doubleValue() > buy_price.doubleValue()) {
                                        log.info("Setup new stop order with price: {}", price);
                                        SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                                    }
                                    else
                                    {
                                        log.info("Calculated price is less or equal to buy price");
                                    }
                                }
                            }
                        }
                    }
                }
                else
                {
                    log.info("Dividend date is soon. Check if profit more than dividends ---------------->");
                    log.info("Check stop orders");
                    if(CheckStopOrder(position.getFigi()))
                    {
                        log.info("Stop order exist");
                        BigDecimal stop_price = getStopOrderPrice(position.getFigi());
                        BigDecimal buy_price = position.getAveragePositionPrice().getValue();
                        BigDecimal cost_now = stop_price.multiply(position.getQuantity());
                        BigDecimal cost_before = buy_price.multiply(position.getQuantity());
                        BigDecimal profit = cost_now.subtract(cost_before).divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
                        BigDecimal dividends = getDividentAmount(position.getFigi());
                        if (profit.doubleValue() > dividends.doubleValue()) {
                            log.info("Profit more than dividends: {} and {}! ---------------->",profit,dividends);
                            BigDecimal profit_percent = (profit.multiply(BigDecimal.valueOf(100)).divide(dividends, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
                            log.info("Profit percent is {}", profit_percent);
                            if (profit_percent.doubleValue() > 0.9) {
                                log.info("Profit percent more than 0.9! Setup stop-market order ---------------->");
                                BigDecimal price = getCurrentBuyPrice(position.getFigi());
                                if (price == null) {
                                    log.info("order-book is unavailable");
                                } else {
                                    price = price.subtract(price.multiply(BigDecimal.valueOf(0.015)));
                                    //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                                    if(price.doubleValue() > stop_price.doubleValue()) {
                                        CancelStopOrders(position.getFigi());
                                        log.info("Setup stop order with price {}", price);
                                        SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                                    }
                                    else
                                    {
                                        log.info("Calculated price is less or equal to stop price");
                                    }
                                }
                            }
                        } else {
                            log.info("Profit is less then dividends: {} and {}",profit,dividends);
                        }
                    }
                    else {
                        log.info("Stop order does not exist");
                        BigDecimal profit = position.getExpectedYield().divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
                        BigDecimal dividends = getDividentAmount(position.getFigi());
                        if (profit.doubleValue() > dividends.doubleValue()) {
                            log.info("Profit more than dividends: {} and {}! ---------------->",profit,dividends);
                            BigDecimal profit_percent = (profit.multiply(BigDecimal.valueOf(100)).divide(dividends, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
                            log.info("Profit percent is {}", profit_percent);
                            if (profit_percent.doubleValue() > 0.5) {
                                BigDecimal price = getCurrentBuyPrice(position.getFigi());
                                log.info("Profit percent more than 0.5! Setup stop-market order with price {} ---------------->", price);
                                if (price == null) {
                                    log.info("order-book is unavailable");
                                } else {
                                    //BigDecimal price = getStopOrderPrice(position.getFigi());
                                    //price = price.subtract(price.multiply(BigDecimal.valueOf(0.015)));
                                    //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                                    SetupStopMarket(api, position.getFigi(), price,position.getQuantity().longValue());
                                }
                            }
                        } else {
                            log.info("Profit is less then dividends: {} and {}",profit,dividends);
                        }
                    }
                }
            }
        }
    }

    private void CheckSellStrategy2()
    {

    }

    private void CheckBuyStrategy1()
    {

    }

    private void CheckBuyStrategy2()
    {

    }

    private static BigDecimal getCurrentBuyPrice(String figi)
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

    private static boolean CheckAvailableMoney(String currency, double sharePrice)
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

    private static void BuyShareByMarketPrice(String figi)
    {
        log.debug("BuyShareByMarketPrice");
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();

        var lastPrice = api.getMarketDataService().getLastPricesSync(List.of(figi)).get(0).getPrice();
        var minPriceIncrement = api.getInstrumentsService().getInstrumentByFigiSync(figi).getMinPriceIncrement();
        var price = Quotation.newBuilder().setUnits(lastPrice.getUnits() - minPriceIncrement.getUnits())
                .setNano(lastPrice.getNano() - minPriceIncrement.getNano()).build();

        //System.out.println("Min increment - " + minPriceIncrement.getUnits() + "," + minPriceIncrement.getNano());
        log.info("Last price - " + lastPrice.getUnits() + "," + lastPrice.getNano());
        log.info("Buy price -  " + price.getUnits() + "," + price.getNano());

        var orderId = api.getOrdersService()
                .postOrderSync(figi, 1, price, OrderDirection.ORDER_DIRECTION_BUY, mainAccount, OrderType.ORDER_TYPE_MARKET,
                        UUID.randomUUID().toString()).getOrderId();
        log.info("Buy order id: {}",orderId);
    }

    private static Double GetAverageMonthPrice(String figi)
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

    /*private static void printCandle(HistoricCandle candle) {
        var open = quotationToBigDecimal(candle.getOpen());
        var close = quotationToBigDecimal(candle.getClose());
        var high = quotationToBigDecimal(candle.getHigh());
        var low = quotationToBigDecimal(candle.getLow());
        var volume = candle.getVolume();
        var time = timestampToString(candle.getTime());
        log.info(
                "open price: {}, close price: {}, minimum price for 1 lot: {}, maximum price for 1 lot: {}, value of " +
                        "lots: {}, time of candle: {}",
                open, close, low, high, volume, time);
    }*/

    private static List<Position> GetShares()
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

    /*private static Double GetProfit(String figi, InvestApi api)
    {
        log.info("GetProfit");
        double result = 0;
        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = accounts.get(0).getId();
        var portfolio = api.getOperationsService().getPortfolioSync(mainAccount);
        var positions = portfolio.getPositions();
        //log.info("amount {} positions", positions.size());
        for (int i = 0; i < positions.size(); i++) {
            var position = positions.get(i);
            var pos_figi = position.getFigi();
            //var quantity = position.getQuantity();
            //var currentPrice = position.getCurrentPrice();
            var expectedYield = position.getExpectedYield().doubleValue();
            Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(pos_figi);
            if(figi.equals(pos_figi)) {
                log.info(
                        "ticker: {}, profitability: {}",
                        ins.getTicker(), expectedYield);
                result = expectedYield;
                break;
            }
        }
        return result;
    }*/

    private static void CancelStopOrders(String figi)
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

    private static BigDecimal getStopOrderPrice(String figi)
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

    private static boolean CheckStopOrder(String figi)
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

    private static void SetupStopMarket(InvestApi api, String figi, BigDecimal price, long quantity)
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

    private static BigDecimal getDividentAmount(String figi)
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

    private static boolean CheckDividendDate(String figi)
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
