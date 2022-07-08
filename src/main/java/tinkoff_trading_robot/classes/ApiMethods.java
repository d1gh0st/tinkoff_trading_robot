package tinkoff_trading_robot.classes;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.OperationsService;
import ru.tinkoff.piapi.core.UsersService;
import ru.tinkoff.piapi.core.models.Money;
import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static ru.tinkoff.piapi.core.utils.DateUtils.timestampToString;
import static ru.tinkoff.piapi.core.utils.MapperUtils.quotationToBigDecimal;

public class ApiMethods {

    static final Logger log = LoggerFactory.getLogger(ApiMethods.class);

    public String account;

    private InvestApi api;

    public ApiMethods(InvestApi investApi, String acc)
    {
        account = acc;
        api = investApi;
    }

    public void setAccount(String acc)
    {
        account = acc;
    }

    public String getAccount()
    {
        return account;
    }

    public boolean checkTradingStatus(String figi)
    {
        boolean result = false;
        try {
            var tradingStatus = api.getMarketDataService().getTradingStatusSync(figi);
            if(tradingStatus.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING) result = true;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            try {
                Thread.sleep(60000);
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
            }
        }
        return result;
    }

    public BigDecimal getCurrentBuyPrice(String figi)
    {
        log.debug("getCurrentBuyPrice");
        BigDecimal price = BigDecimal.valueOf(0);
        try {
            var depth = 10;
            var orderBook = api.getMarketDataService().getOrderBookSync(figi, depth);
            var bids = orderBook.getBidsList();

            if(bids.size() > 0)
                price = quotationToBigDecimal(bids.get(0).getPrice());
            else price = null;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return price;
    }

    public BigDecimal getCurrentSellPrice(String figi)
    {
        log.debug("getCurrentSellPrice");
        BigDecimal price = BigDecimal.valueOf(0);
        try {
            var depth = 10;
            var orderBook = api.getMarketDataService().getOrderBookSync(figi, depth);
            var asks = orderBook.getAsksList();

            if(asks.size() > 0)
                price = quotationToBigDecimal(asks.get(0).getPrice());
            else price = null;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return price;
    }

    public boolean CheckAvailableMoney(String currency, double sharePrice)
    {
        log.debug("CheckAvailableMoney");
        UsersService usersService = api.getUserService();
        if(usersService != null) {
            var accounts = usersService.getAccountsSync();
            var mainAccount = account;

            var positions = api.getOperationsService().getPositionsSync(mainAccount);


            var moneyList = positions.getMoney();
            for (Money moneyValue : moneyList) {
                if (currency.equals(moneyValue.getCurrency().getCurrencyCode().toLowerCase(Locale.ROOT))) {
                    log.info("currency: {}, value: {}", moneyValue.getCurrency(), moneyValue.getValue());
                    double balance = moneyValue.getValue().doubleValue();
                    balance /= 3;
                    if (balance > sharePrice) {
                        return true;
                    } else return false;
                }
            }
        }

        return false;
    }



    public void BuyShareByMarketPrice(String figi)
    {
        log.debug("BuyShareByMarketPrice");
        UsersService usersService = api.getUserService();
        if(usersService != null) {
            var accounts = usersService.getAccountsSync();
            var mainAccount = account;

            var lastPrice = api.getMarketDataService().getLastPricesSync(List.of(figi)).get(0).getPrice();
            InstrumentsService service = api.getInstrumentsService();
            if (service != null) {
                var minPriceIncrement = service.getInstrumentByFigiSync(figi).getMinPriceIncrement();
                var price = Quotation.newBuilder().setUnits(lastPrice.getUnits() - minPriceIncrement.getUnits())
                        .setNano(lastPrice.getNano() - minPriceIncrement.getNano()).build();

                log.info("Last price - " + lastPrice.getUnits() + "," + lastPrice.getNano());
                log.info("Buy price -  " + price.getUnits() + "," + price.getNano());

                var orderId = api.getOrdersService()
                        .postOrderSync(figi, 1, price, OrderDirection.ORDER_DIRECTION_BUY, mainAccount, OrderType.ORDER_TYPE_MARKET,
                                UUID.randomUUID().toString()).getOrderId();
                log.info("Buy order id: {}", orderId);
            }
        }
    }

    public void SellShareByMarketPrice(String figi)
    {
        log.debug("SellShareByMarketPrice");
        UsersService usersService = api.getUserService();
        if(usersService != null) {
            var accounts = usersService.getAccountsSync();
            var mainAccount = account;

            var lastPrice = api.getMarketDataService().getLastPricesSync(List.of(figi)).get(0).getPrice();
            InstrumentsService service = api.getInstrumentsService();
            if (service != null) {
                var minPriceIncrement = service.getInstrumentByFigiSync(figi).getMinPriceIncrement();
                var price = Quotation.newBuilder().setUnits(lastPrice.getUnits() - minPriceIncrement.getUnits())
                        .setNano(lastPrice.getNano() - minPriceIncrement.getNano()).build();

                log.info("Last price - " + lastPrice.getUnits() + "," + lastPrice.getNano());
                log.info("Sell price -  " + price.getUnits() + "," + price.getNano());

                var orderId = api.getOrdersService()
                        .postOrderSync(figi, 1, price, OrderDirection.ORDER_DIRECTION_SELL, mainAccount, OrderType.ORDER_TYPE_MARKET,
                                UUID.randomUUID().toString()).getOrderId();
                log.info("Sell order id: {}", orderId);
            }
        }
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


    public List<HistoricCandle> getPrices(Share share)
    {
        //log.info("Ticker - {}",share.getTicker());
        List<HistoricCandle> result = new ArrayList<>();
        //List<HistoricCandle> candlesOneDays = new ArrayList<>();
        int size = 0;
        Instant start = Instant.now().minus(160, ChronoUnit.HOURS);
        Instant end = Instant.now();
        //Date date = Date.from(end);
        //int hour = date.getHours();
        /*if(hour > 0 && hour < 10)
        {

        }
        if(hour > 10 && hour < 14)
        {

        }
        if(hour > 14 && hour < 18)
        {
            update = 14 - hour;
        }
        if(hour > 18) update = 18 - hour;*/
        //end = Instant.now().minus(update,ChronoUnit.HOURS);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        List<HistoricCandle> temp = new ArrayList<>();
        int counter = 0;
        while (size<100) {
            counter++;
            //log.info("Download prices... current size - {}",size);
            try {
                List<HistoricCandle> candles = api.getMarketDataService()
                        .getCandlesSync(share.getFigi(), start, end,
                                CandleInterval.CANDLE_INTERVAL_HOUR);
                //size += candles.size();
                for (int i = candles.size() - 1; i >= 0; i--) {
                    HistoricCandle candle = candles.get(i);
                    if (size >= 100) break;
                /*if(i > 1)
                {
                    HistoricCandle nextCandle = candles.get(i-1);
                    //if(nextCandle.getTime().equals(candle.getTime())) continue;
                }*/
                    try {
                        Date date = sdf.parse(timestampToString(candle.getTime()));
                        int hour = date.getHours();
                        Calendar c = Calendar.getInstance();
                        c.setTime(date);
                        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);
                        //int minutes = date.getMinutes();
                        //log.info("Ticker: {}, day of the week: {}", share.getTicker(), dayOfWeek);
                        if(dayOfWeek != 7 && dayOfWeek != 1) {
                            if (share.getCurrency().equals("rub")) {
                                if (hour == 8 || hour == 12 || hour == 15) {
                                    temp.add(candles.get(i));
                                    size++;
                                }
                            }
                            if (share.getCurrency().equals("usd")) {
                                if (hour == 13 || hour == 17) {
                                    temp.add(candles.get(i));
                                    size++;
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                start = start.minus(160, ChronoUnit.HOURS);
                end = end.minus(160, ChronoUnit.HOURS);
                /*try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    e.printStackTrace();
                }*/
            }
            catch (Exception e)
            {
                e.printStackTrace();
                log.info("Waiting..");
                try {
                    Thread.sleep(60000);
                }
                catch (Exception e1)
                {
                    e1.printStackTrace();
                }

            }

        }
        long volume = 0;
        for(int i=temp.size()-1;i>=0;i--)
        {
            volume += temp.get(i).getVolume();
            result.add(temp.get(i));
        }
        //log.info("Ticker - {}, count size times: {}",share.getTicker(), counter);
        //result = temp;
        //log.info("volume: {}",volume);
        return result;
    }

    public MyShare getDaysPrices(Share share)
    {

        List<BigDecimal> prices = new ArrayList<>();
        List<Integer> date_intervals = new ArrayList<>();
        //long diff = date2.getTime() - date1.getTime();
        //long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            //List<HistoricCandle> candlesOneDays = new ArrayList<>();
            List<HistoricCandle> candlesOneDays = api.getMarketDataService()
                    .getCandlesSync(share.getFigi(), Instant.now().minus(14, ChronoUnit.DAYS), Instant.now(),
                            CandleInterval.CANDLE_INTERVAL_DAY);
            /*List<HistoricCandle> temp = new ArrayList<>();
            int size = 0;
            Instant start = Instant.now().minus(100, ChronoUnit.DAYS);
            Instant end = Instant.now();
            while (size<100) {
                List<HistoricCandle> candles = api.getMarketDataService()
                        .getCandlesSync(share.getFigi(), start, end,
                                CandleInterval.CANDLE_INTERVAL_DAY);
                //size += candles.size();
                for(int i=candles.size()-1;i>=0;i--)
                {
                    HistoricCandle candle = candles.get(i);
                    if(size >= 100) break;
                    if(i > 1)
                    {
                        HistoricCandle nextCandle = candles.get(i-1);
                        if(nextCandle.getTime().equals(candle.getTime())) continue;
                    }
                    temp.add(candles.get(i));
                    size++;
                }
                start = start.minus(100, ChronoUnit.DAYS);
                end = end.minus(100, ChronoUnit.DAYS);
            }
            for(int i=temp.size()-1;i>=0;i--) candlesOneDays.add(temp.get(i));*/
            long volume = 0;
            if (candlesOneDays.size() > 0) {
                for (int i=0;i<candlesOneDays.size();i++) {
                    HistoricCandle candle = candlesOneDays.get(i);
                    Date date = sdf.parse(timestampToString(candle.getTime()));
                    //log.info("Time of the candle: {}",date);
                    volume += candle.getVolume();
                    if(i!=0) {
                        Date date1 = sdf.parse(timestampToString(candlesOneDays.get(i-1).getTime()));
                        Date date2 = sdf.parse(timestampToString(candle.getTime()));
                        long diff = date2.getTime() - date1.getTime();
                        long days = TimeUnit.MILLISECONDS.toDays(diff);
                        date_intervals.add((int) days);

                    }
                    //log.info("price: {}",quotationToBigDecimal(candle.getClose()).doubleValue());
                    prices.add(quotationToBigDecimal(candle.getClose()));

                }
                log.info("ticker: {}, volume: {}",share.getTicker(),volume);
                if(volume < 100000 && share.getCurrency().equals("RUB"))
                {
                    //log.debug("ticker: {}, low liquidity",share.getTicker());
                    date_intervals.clear();
                    prices.clear();
                }
                if(volume < 1000000 && !share.getCurrency().equals("RUB"))
                {
                    date_intervals.clear();
                    prices.clear();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        MyShare myShare = new MyShare(prices,date_intervals);
        return myShare;
    }

    public BigDecimal getLastPrice(Share share)
    {
        List<String> list = new ArrayList<>();
        list.add(share.getFigi());
        BigDecimal result = BigDecimal.valueOf(0);
        boolean success = false;
        while (!success) {
            try {
                var lastPrices = api.getMarketDataService().getLastPricesSync(list);
                for (LastPrice lastPrice : lastPrices) {
                    var price = quotationToBigDecimal(lastPrice.getPrice());
                    result = price;
                    var time = timestampToString(lastPrice.getTime());
                    log.debug("last price for the instrument {}, price: {}, time of refreshing: {}", share.getTicker(), price, time);
                }
                success = true;
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    Thread.sleep(60000);
                    log.info("Waiting..");
                }
                catch (Exception e1)
                {
                    e1.printStackTrace();
                }
                success = false;
            }
        }
        return result;
    }

    public List<Position> GetShares()
    {
        log.debug("GetShares");
        List<Position> result = new ArrayList<>();
        try {
            UsersService usersService = api.getUserService();
            if (usersService != null) {
                var accounts = usersService.getAccountsSync();
                var mainAccount = account;
                OperationsService operationsService = api.getOperationsService();
                if (operationsService != null) {
                    var portfolio = operationsService.getPortfolioSync(mainAccount);
                    var positions = portfolio.getPositions();
                    //log.info("amount {} positions", positions.size());
                    for (int i = 0; i < positions.size(); i++) {
                        var position = positions.get(i);
                        result.add(position);
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return result;
    }

    public List<Share> GetAllShares()
    {
        return api.getInstrumentsService().getTradableSharesSync();
    }

    public void CancelStopOrders(String figi)
    {
        try {
            log.debug("CancelStopOrders");
            UsersService usersService = api.getUserService();
            if (usersService != null) {
                var accounts = usersService.getAccountsSync();
                var mainAccount = account;
                var stopOrders = api.getStopOrdersService().getStopOrdersSync(mainAccount);
                for (StopOrder order : stopOrders) {
                    if (order.getFigi().equals(figi)) {
                        api.getStopOrdersService().cancelStopOrder(mainAccount, order.getStopOrderId());
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public BigDecimal getStopOrderPrice(String figi)
    {
        log.debug("getStopOrderPrice");
        UsersService usersService = api.getUserService();
        if(usersService != null) {
            var accounts = usersService.getAccountsSync();
            var mainAccount = account;
            var stopOrders = api.getStopOrdersService().getStopOrdersSync(mainAccount);
            for (StopOrder order : stopOrders) {
                if (order.getFigi().equals(figi)) {
                    MoneyValue moneyValue = order.getStopPrice();
                    BigDecimal price = moneyValue.getUnits() == 0 && moneyValue.getNano() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(moneyValue.getUnits()).add(BigDecimal.valueOf(moneyValue.getNano(), 9));
                    return price;
                }
            }
        }
        return null;
    }

    public boolean CheckStopOrder(String figi)
    {
        try {
            log.debug("CheckStopOrder");
            UsersService usersService = api.getUserService();
            if (usersService != null) {
                var accounts = usersService.getAccountsSync();
                var mainAccount = account;
                var stopOrders = api.getStopOrdersService().getStopOrdersSync(mainAccount);
                for (StopOrder order : stopOrders) {
                    if (order.getFigi().equals(figi)) {

                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return false;
    }

    /*private MoneyValue GetCurrentPrice(Share share)
    {
        return null;
    }*/


    public void SetupBuyStopMarket(InvestApi api, String figi, BigDecimal price, long quantity) {
        log.debug("SetupStopMarket");
        UsersService usersService = api.getUserService();
        if (usersService != null) {
            var accounts = usersService.getAccountsSync();
            var mainAccount = account;
            BigDecimal new_price = BigDecimal.valueOf(0);
            InstrumentsService service = api.getInstrumentsService();
            if (service != null) {
                var minPriceIncrement = service.getInstrumentByFigiSync(figi).getMinPriceIncrement();
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
                        .postStopOrderGoodTillDateSync(figi, quantity, stopPrice, stopPrice, StopOrderDirection.STOP_ORDER_DIRECTION_BUY,
                                mainAccount, StopOrderType.STOP_ORDER_TYPE_STOP_LOSS, Instant.now().plus(30, ChronoUnit.DAYS));
                log.info("Stop-order id: {}", stopOrderId);
            }
        }
    }

    public void SetupStopMarket(InvestApi api, String figi, BigDecimal price, long quantity)
    {
        log.debug("SetupStopMarket");
        UsersService usersService = api.getUserService();
        if(usersService != null) {
            var accounts = usersService.getAccountsSync();
            var mainAccount = account;
            BigDecimal new_price = BigDecimal.valueOf(0);
            InstrumentsService service = api.getInstrumentsService();
            if (service != null) {
                var minPriceIncrement = service.getInstrumentByFigiSync(figi).getMinPriceIncrement();
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
                        .postStopOrderGoodTillDateSync(figi, quantity, stopPrice, stopPrice, StopOrderDirection.STOP_ORDER_DIRECTION_SELL,
                                mainAccount, StopOrderType.STOP_ORDER_TYPE_STOP_LOSS, Instant.now().plus(30, ChronoUnit.DAYS));
                log.info("Stop-order id: {}", stopOrderId);
            }
        }
    }

    public BigDecimal getDividentAmount(String figi)
    {
        log.debug("getDividentAmount");
        BigDecimal amount = null;
        InstrumentsService service = api.getInstrumentsService();
        if(service != null) {
            Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(figi);
            if (ins.getInstrumentType().equals("share")) {
                var dividends =
                        api.getInstrumentsService().getDividendsSync(figi, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
                if (dividends.size() > 0) {
                    MoneyValue moneyValue = dividends.get(0).getDividendNet();
                    amount = moneyValue.getUnits() == 0 && moneyValue.getNano() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(moneyValue.getUnits()).add(BigDecimal.valueOf(moneyValue.getNano(), 9));
                    amount = amount.multiply(BigDecimal.valueOf(ins.getLot()));
                }
            }
        }
        return amount;
    }

    public boolean CheckDividendDate(String figi)
    {
        try {
            Thread.sleep(1000);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        log.debug("CheckDividendDate");
        InstrumentsService service = api.getInstrumentsService();
        if(service != null) {
            Instrument ins = service.getInstrumentByFigiSync(figi);
            if (ins.getInstrumentType().equals("share")) {
                var dividends =
                        api.getInstrumentsService().getDividendsSync(figi, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
                for (Dividend dividend : dividends) {
                    log.info("Dividends for ticker: {}. Amount: {}, Currency: {}, Date: {}", ins.getTicker(),
                            dividend.getDividendNet().getUnits() + "," + dividend.getDividendNet().getNano(),
                            dividend.getDividendNet().getCurrency(), dividend.getRecordDate().getSeconds());
                    return true;
                }
            }
        }
        return false;
    }




    public void getOperations(InvestApi api) throws ParseException {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");


        Date date2 = new Date();


        var accounts = api.getUserService().getAccountsSync();
        var mainAccount = account;


        var operations = api.getOperationsService()
                .getAllOperationsSync(mainAccount, Instant.now().minus(30, ChronoUnit.DAYS), Instant.now());
        long days = 0;
        int i=0;
        double sum_rub = 0;
        double sum_usd = 0;
        while (days < 7 && i < operations.size())
        {
            var operation = operations.get(i);
            i++;
            var date = timestampToString(operation.getDate());
            Date date1 = sdf.parse(date);
            long diff = date2.getTime() - date1.getTime();
            days = TimeUnit.MILLISECONDS.toDays(diff);
            //log.info("Days duration: {}", days);
            var state = operation.getState().name();
            var id = operation.getId();
            var pay = operation.getPayment();
            BigDecimal payment = pay.getUnits() == 0 && pay.getNano() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(pay.getUnits()).add(BigDecimal.valueOf(pay.getNano(), 9));
            var figi = operation.getFigi();
            try {
                if(state.equals("OPERATION_STATE_EXECUTED"))
                {
                    //log.info("test!");
                    if(operation.getCurrency().equals("rub")) sum_rub += payment.doubleValue();
                    if(operation.getCurrency().equals("usd")) sum_usd += payment.doubleValue();
                    //log.info("income - {} RUB" , sum_rub);
                    //log.info("income - {} USD" , sum_usd);
                }
                //Instrument ins = api.getInstrumentsService().getInstrumentByFigiSync(figi);
                //log.info("Operation with id: {}, date: {}, status: {}, payment: {}, ticker: {}", id, date, state, payment, ins.getTicker());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        log.info("Income for the last week USD: {}",sum_usd);
        log.info("Income for the last week RUB: {}",sum_rub);
    }
}
