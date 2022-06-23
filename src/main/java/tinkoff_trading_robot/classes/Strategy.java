package tinkoff_trading_robot.classes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Position;
import tinkoff_trading_robot.MainScenario;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

public class Strategy {

    static final Logger log = LoggerFactory.getLogger(Strategy.class);
    private ApiMethods apiMethods;
    private InvestApi api;
    private List<Share> shares;
    //Map<String,List<BigDecimal>> shareListMap;
    //int downloaded_prices;
    //int price_requested;
    int counter;
    Map<String,BigDecimal> first_level_buy;
    Map<String,BigDecimal> second_level_buy;
    Map<String,BigDecimal> third_level_buy;
    Map<String,BigDecimal> first_level_sell;
    Map<String,BigDecimal> second_level_sell;
    Map<String,BigDecimal> third_level_sell;
    List<String> trends;

    public Strategy(InvestApi investApi)
    {
        api = investApi;
        apiMethods = new ApiMethods(api);
        log.info("Download shares");
        shares = downloadShares();
        //shareListMap = new HashMap<>();
        //downloaded_prices = 0;
        //price_requested = 0;
        counter = 0;
        first_level_buy = new HashMap<>();
        second_level_buy = new HashMap<>();
        third_level_buy = new HashMap<>();
        first_level_sell = new HashMap<>();
        second_level_sell = new HashMap<>();
        third_level_sell = new HashMap<>();
        trends = new ArrayList<>();
        //shareListMap = downloadPrices();
    }


    private void DividendDateDoesNotExist(Position position, Instrument ins)
    {
        log.info("Dividend date does not exist in the nearest future ---------------->");
        BigDecimal profit = position.getExpectedYield().divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
        log.info("profit: {}",profit);
        if(profit.doubleValue() > 0) {
            log.info("Profit more then zero ---------------->");
            log.info("Check stop orders");
            if(apiMethods.CheckStopOrder(position.getFigi()))
            {
                StopOrderExist(position, ins);
            }
            else {
                StopOrderDoesNotExist(position,profit,ins);
            }
        }
    }

    private void DividendDateExist(Position position)
    {
        log.info("Dividend date is soon. Check if profit more than dividends ---------------->");
        log.info("Check stop orders");
        if(apiMethods.CheckStopOrder(position.getFigi()))
        {
            StopOrderExist_DividendDate(position);
        }
        else {
            StopOrderDoesNotExist_DividendDate(position);
        }
    }

    private void StopOrderExist(Position position, Instrument ins)
    {
        log.info("Stop order exist ---------------->");
        BigDecimal current_price = apiMethods.getCurrentBuyPrice(position.getFigi());
        if(current_price == null)
        {
            log.info("order-book is unavailable");
        }
        else {
            log.info("Current buy price: {}", current_price);
            BigDecimal buy_price = apiMethods.getStopOrderPrice(position.getFigi());
            log.info("Stop order price: {}", buy_price);
            BigDecimal profit = current_price.subtract(buy_price);
            log.info("Profit from stop order price: {}", profit);
            BigDecimal profit_percent = (current_price.multiply(BigDecimal.valueOf(100)).divide(buy_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent: {}", profit_percent);
            if (profit_percent.doubleValue() > 0.5) {
                log.info("Profit more than 0.5! ---------------->");
                BigDecimal price = buy_price.add(profit.divide(BigDecimal.valueOf(ins.getLot()),2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(ins.getLot()), 2, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                if(price.doubleValue() > buy_price.doubleValue()) {
                    log.info("Setup new stop order with price: {}", price);
                    apiMethods.CancelStopOrders(position.getFigi());
                    apiMethods.SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                }
                else
                {
                    log.info("Calculated price is less or equal to stop price");
                }
            }
        }
    }

    private void StopOrderDoesNotExist(Position position, BigDecimal profit, Instrument ins)
    {
        log.info("Stop order does not exist ---------------->");
        BigDecimal current_price = apiMethods.getCurrentBuyPrice(position.getFigi());
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
                    apiMethods.SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
                }
                else
                {
                    log.info("Calculated price is less or equal to buy price");
                }
            }
        }
    }

    private void StopOrderExist_DividendDate(Position position)
    {
        log.info("Stop order exist");
        BigDecimal stop_price = apiMethods.getStopOrderPrice(position.getFigi());
        BigDecimal buy_price = position.getAveragePositionPrice().getValue();
        BigDecimal cost_now = stop_price.multiply(position.getQuantity());
        BigDecimal cost_before = buy_price.multiply(position.getQuantity());
        BigDecimal profit = cost_now.subtract(cost_before).divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
        BigDecimal dividends = apiMethods.getDividentAmount(position.getFigi());
        if (profit.doubleValue() > dividends.doubleValue()) {
            log.info("Profit more than dividends: {} and {}! ---------------->",profit,dividends);
            BigDecimal profit_percent = (profit.multiply(BigDecimal.valueOf(100)).divide(dividends, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent is {}", profit_percent);
            if (profit_percent.doubleValue() > 0.9) {
                log.info("Profit percent more than 0.9! Setup stop-market order ---------------->");
                BigDecimal price = apiMethods.getCurrentBuyPrice(position.getFigi());
                if (price == null) {
                    log.info("order-book is unavailable");
                } else {
                    price = price.subtract(price.multiply(BigDecimal.valueOf(0.015)));
                    //price = price.divide(BigDecimal.valueOf(1),1, RoundingMode.HALF_UP);
                    if(price.doubleValue() > stop_price.doubleValue()) {
                        apiMethods.CancelStopOrders(position.getFigi());
                        log.info("Setup stop order with price {}", price);
                        apiMethods.SetupStopMarket(api, position.getFigi(), price, position.getQuantity().longValue());
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

    private void StopOrderDoesNotExist_DividendDate(Position position)
    {
        log.info("Stop order does not exist");
        BigDecimal profit = position.getExpectedYield().divide(position.getQuantityLots(),2, RoundingMode.HALF_UP);
        BigDecimal dividends = apiMethods.getDividentAmount(position.getFigi());
        if (profit.doubleValue() > dividends.doubleValue()) {
            log.info("Profit more than dividends: {} and {}! ---------------->",profit,dividends);
            BigDecimal profit_percent = (profit.multiply(BigDecimal.valueOf(100)).divide(dividends, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent is {}", profit_percent);
            if (profit_percent.doubleValue() > 0.9) {
                BigDecimal price = apiMethods.getCurrentBuyPrice(position.getFigi());
                log.info("Profit percent more than 0.9! Setup stop-market order with price {} ---------------->", price);
                if (price == null) {
                    log.info("order-book is unavailable");
                } else {
                    apiMethods.SetupStopMarket(api, position.getFigi(), price,position.getQuantity().longValue());
                }
            }
        } else {
            log.info("Profit is less then dividends: {} and {}",profit,dividends);
        }
    }


    public void CheckSellStrategy()
    {
        List<Position> positions = apiMethods.GetShares();
        for(Position position : positions)
        {
            if(position.getInstrumentType().equals("share")) {
                InstrumentsService instrumentsService = api.getInstrumentsService();
                if(instrumentsService != null) {
                    Instrument ins = instrumentsService.getInstrumentByFigiSync(position.getFigi());
                    log.info("Check dividend date for {} ---------------->", ins.getTicker());
                    log.debug("Quantity: {}", position.getQuantityLots());
                    log.debug("amount of shares in 1 lot: {}", ins.getLot());
                    if (!apiMethods.CheckDividendDate(position.getFigi())) {
                        DividendDateDoesNotExist(position, ins);
                    } else {
                        DividendDateExist(position);
                    }
                }
            }
        }
    }


    public void CheckTrendSecondLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = second_level_buy.get(ticker);
        if(prevBuyPrice != null)
            log.info("Second level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            log.info("Second level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
            sendToTelegram("Second level: Trend confirmed! Buy! Ticker: " + ticker);
            third_level_buy.put(ticker,price);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() < prevBuyPrice.doubleValue())
        {
            first_level_buy.remove(ticker);
            second_level_buy.remove(ticker);
            trends.remove(ticker);
        }
        BigDecimal prevSellPrice = second_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("Second level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            log.info("Second level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
            sendToTelegram("Second level: Trend confirmed! Sell! Ticker: " + ticker);
            third_level_sell.put(ticker,price);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else if(price.doubleValue() > prevSellPrice.doubleValue())
        {
            first_level_sell.remove(ticker);
            second_level_sell.remove(ticker);
            trends.remove(ticker);
        }
    }

    public void CheckTrendFirstLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = first_level_buy.get(ticker);
        if(prevBuyPrice != null)
        log.info("First level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            log.info("First level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
            sendToTelegram("First level: Trend confirmed! Buy! Ticker: " + ticker);
            second_level_buy.put(ticker,price);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() < prevBuyPrice.doubleValue())
        {
            first_level_buy.remove(ticker);
            trends.remove(ticker);
        }
        BigDecimal prevSellPrice = first_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("First level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            log.info("First level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
            sendToTelegram("First level: Trend confirmed! Sell! Ticker: " + ticker);
            second_level_sell.put(ticker,price);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else  if(price.doubleValue() > prevSellPrice.doubleValue())
        {
            first_level_sell.remove(ticker);
            trends.remove(ticker);
        }
    }

    public void CheckTrends()
    {
        List<String> work_array = new ArrayList<>(trends);
        for(String ticker : work_array)
        {
            Share trendShare = null;
            for(Share share : shares)
            {
                if (share.getTicker().equals(ticker))
                {
                    trendShare = share;
                    break;
                }
            }
            if(trendShare != null)
            {
                BigDecimal new_price = apiMethods.getLastPrice(trendShare);
                if(new_price.doubleValue() == 0) continue;
                CheckTrendSecondLevel(new_price,ticker);
                CheckTrendFirstLevel(new_price,ticker);
            }

        }
    }

    public void BuyStrategy()
    {
        if(counter >= shares.size())
        {
            counter = 0;
            shares = downloadShares();
            if(shares.size() == 0)
            {
                log.info("Market seems to be closed");
                return;
            }
        }
        CheckTrends();
        int lastBoundary = counter + 100;
        if(lastBoundary > shares.size()) lastBoundary = shares.size();
        int sma_count = 0;
        for(int i=counter;i<lastBoundary;i++)
        {
            Share share = shares.get(i);
            List<BigDecimal> prices = apiMethods.getDaysPrices(share);
            BigDecimal new_price = apiMethods.getLastPrice(share);
            //CheckTrendFirstLevel(new_price, share.getTicker());
            //CheckTrendSecondLevel(new_price, share.getTicker());
            if(prices.size() >= 9 && new_price.doubleValue() != 0) {
                sma_count++;
                SMA(share, prices, new_price);
            }
        }
        log.info("SMA counted: {}",sma_count);
        log.info("SMA buy breakdowns:{}",first_level_buy.size());
        log.info("SMA sell breakdowns:{}",first_level_sell.size());
        log.info("Current range: from {} to {}",counter,lastBoundary);
        counter+=100;
    }

    /*public void BuyStrategy1()
    {
        if(counter >= shares.size()) counter = 0;
        int lastBoundary = counter + 100;
        if(lastBoundary > shares.size()) lastBoundary = shares.size();
        int sma_count = 0;
        for(int i=counter;i<lastBoundary;i++)
        {
            Share share = shares.get(i);
            List<BigDecimal> prices = getPricesArray2(share);
            BigDecimal new_price = getLastPrice(share);
            CheckTrendFirstLevel(new_price, share.getTicker());
            CheckTrendSecondLevel(new_price, share.getTicker());
            if(prices.size() >= 9) {
                sma_count++;
                SMA(share, prices, new_price);
            }
        }
        log.info("SMA counted: {}",sma_count);
        log.info("SMA buy breakdowns:{}",first_level_buy.size());
        log.info("SMA sell breakdowns:{}",first_level_sell.size());
        log.info("Current range: from {} to {}",counter,lastBoundary);
        counter+=100;
    }

    public void BuyStrategy()
    {
        //Date date = new Date();
        //if(date.getMinutes() == 0)
        int d_prices1 = downloaded_prices;
        if(downloaded_prices < shares.size())
        shareListMap = downloadPrices();
        else log.info("Historical prices loaded");
        log.info("shareListMap size: {}", shareListMap.size());
        int d_price2 = downloaded_prices;
        int sma_count = 0;
        //int price_requested = 0;
        int prices_added_count = 0;
        int local_price_requested = 0;
        if(price_requested >= shares.size()) price_requested = 0;
        for(int i=price_requested;i<shares.size();i++)
        {
            Share share = shares.get(i);
            boolean added_price = false;
            log.debug("Get prices..");
            List<BigDecimal> prices = shareListMap.get(share.getFigi());
            if(prices != null) {
                log.debug("Prices is not null");
                BigDecimal new_price = getLastPrice(share);
                //price_requested++;
                //price_requested++;
                local_price_requested++;
                if(prices.size() >= 9) {
                    log.debug("Prices size more than 9");
                    SMA(share, prices, new_price);
                    sma_count++;

                }
                if(prices.get(prices.size()-1).doubleValue() != new_price.doubleValue()) {
                    added_price = true;
                    prices.add(new_price);
                }
            }
            else if(downloaded_prices >= shares.size() && prices == null)
            {
                BigDecimal new_price = getLastPrice(share);
                prices = new ArrayList<>();
                prices.add(new_price);
                local_price_requested++;
                added_price = true;
            }
            if(share.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING && added_price)
            {
                prices_added_count++;
                shareListMap.put(share.getFigi(),prices);
            }
            if(local_price_requested > 50 && downloaded_prices < shares.size()) break;
            if(local_price_requested > 50 && d_prices1 != d_price2) break;
            if(local_price_requested > 200 && downloaded_prices >= shares.size()) break;
        }
        price_requested += local_price_requested;
        //log.info("new prices added {} times",prices_added_count);
        log.info("Price requested: {}",price_requested);
        log.info("SMA calculated {} times",sma_count);
        //SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING
        //Share sh = null;
        //for (Share share : shares)
        //{
        //    if(share.getTicker().equals("T")) sh = share;
            //log.info("Share status: {}", share.getTradingStatus());
        //}
        //if(sh.getTradingStatus().equals(SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING))
        //List<BigDecimal> prices = getPricesArray(sh);
        //SMA(sh, prices);
        //else log.info("Share is not tradable for now");

    }*/

    public List<Share> downloadShares()
    {
        List<Share> result = new ArrayList<>();
        List<Share> shares = apiMethods.GetAllShares();
        for(Share share : shares)
        {
            if(share.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING) result.add(share);
        }
        return result;
    }

    /*public Map<String,List<BigDecimal>> downloadPrices()
    {
        int amount = shares.size();
        Map<String, List<BigDecimal>> map = shareListMap;
        if(downloaded_prices < amount) {
            log.info("Download historical prices");
            log.info("Amount of shares: {}", amount);
            //List<Share> shares = apiMethods.GetAllShares();
            int count = 0;
            for (int i=downloaded_prices;i<shares.size();i++) {
                Share share = shares.get(i);
                if (count > 200) {
                    try {
                        log.info("Downloaded {} from {}", downloaded_prices, amount);
                        //count = 0;
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (shareListMap.get(share.getFigi()) == null || shareListMap.get(share.getFigi()).size() < 9) {
                    count++;
                    List<BigDecimal> prices = getPricesArray2(share);
                    if (prices.size() > 0) {
                        downloaded_prices++;
                        map.put(share.getFigi(), prices);
                    }
                } else {
                    downloaded_prices++;
                    map.put(share.getFigi(), shareListMap.get(share.getFigi()));
                }

            }
            return map;
        }
        else return shareListMap;
    }*/



    private void SMA(Share share, List<BigDecimal> prices, BigDecimal new_price)
    {
        BigDecimal finalPrice = BigDecimal.valueOf(0);
        log.debug("Calculating SMA");
        String p = "";
        for(int i=prices.size()-9;i<prices.size();i++)
        {
            BigDecimal price = prices.get(i);
            p = p + " " + price.doubleValue();
            finalPrice = finalPrice.add(price);
        }
        log.debug("Prices:{}",p);
        BigDecimal SMA = finalPrice.divide(BigDecimal.valueOf(9),2,RoundingMode.HALF_UP);
        BigDecimal last_price = prices.get(prices.size()-1);
        log.debug("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
        log.debug("new price: {}",new_price);
        if(SMA.doubleValue() > last_price.doubleValue() && new_price.doubleValue() > SMA.doubleValue())
        {
            log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
            log.info("new price: {}",new_price);
            log.info("Ticker {}, SMA Buy!!!", share.getTicker());
            first_level_buy.put(share.getTicker(),new_price);
            trends.add(share.getTicker());
            //sendToTelegram("Ticker " + share.getTicker() + ", SMA Buy!!!");
        }
        if(SMA.doubleValue() < last_price.doubleValue() && new_price.doubleValue() < SMA.doubleValue())
        {
            log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
            log.info("new price: {}",new_price);
            log.info("Ticker {}, SMA Sell!!!", share.getTicker());
            first_level_sell.put(share.getTicker(),new_price);
            trends.add(share.getTicker());
            //sendToTelegram("Ticker " + share.getTicker() + ", SMA Sell!!!");
        }

        //return SMA;
    }




    /*private List<BigDecimal> getPricesArray2(Share share)
    {
        List<BigDecimal> prices = new ArrayList<>();
        var candlesOneMinutes = api.getMarketDataService()
                .getCandlesSync(share.getFigi(), Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now(),
                        CandleInterval.CANDLE_INTERVAL_1_MIN);
        if(candlesOneMinutes.size() > 0) {
            for(HistoricCandle candle : candlesOneMinutes)
            {
                prices.add(quotationToBigDecimal(candle.getClose()));
            }
        }
        return prices;
    }*/

    /*private List<BigDecimal> getPricesArray(Share share)
    {
        List<BigDecimal> finalPrices = new ArrayList<>();
        List<BigDecimal> prices = new ArrayList<>();
        var candlesHour = api.getMarketDataService()
                .getCandlesSync(share.getFigi(), Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now(),
                        CandleInterval.CANDLE_INTERVAL_1_MIN);


        //log.debug("Got {} 1-minutes candles for ticker {}", candlesHour.size(), share.getTicker());



            try {

                /*d1.setMinutes(0);


                String lastDate = Instant.now().toString();
                lastDate = lastDate.substring(0, lastDate.indexOf('.')) + 'Z';
                Date last = sdf.parse(lastDate);

                last.setMinutes(1);

                List<HistoricCandle> candles = new ArrayList<>();

                log.debug("Ticker: {}, candles size: {}",share.getTicker(),candlesHour.size());

                for(int i=0;i<candlesHour.size();i++)
                {
                    HistoricCandle candle = candlesHour.get(i);
                    Date date = sdf.parse(timestampToString(candle.getTime()));
                    if((date.after(d1) && date.before(last)) || date.compareTo(d1) == 0 || date.compareTo(last) == 0) {
                        candles.add(candle);
                    }
                }

                candlesHour = candles;
                log.debug("After filtering - Ticker: {}, candles size: {}",share.getTicker(),candlesHour.size());

                if(candlesHour.size() > 0) {

                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                    String startDate = Instant.now().minus(10, ChronoUnit.MINUTES).toString();
                    startDate = startDate.substring(0, startDate.indexOf('.')) + 'Z';

                    Date d1 = sdf.parse(startDate);
                    HistoricCandle firstCandle = candlesHour.get(0);
                    Date d2 = sdf.parse(timestampToString(firstCandle.getTime()));
                    //System.out.println("current date: " + sdf.parse(d));

                    fillPricesBetweenDates(d1, d2, firstCandle, prices);
                    for (int i = 1; i < candlesHour.size(); i++) {
                        HistoricCandle candle = candlesHour.get(i);

                        Date date1 = sdf.parse(timestampToString(firstCandle.getTime()));
                        Date date2 = sdf.parse(timestampToString(candle.getTime()));
                        fillPricesBetweenDates(date1, date2, candle, prices);
                        firstCandle = candle;
                        //printCandle(candle);
                    }
                    String currentDate = Instant.now().toString();
                    currentDate = currentDate.substring(0, currentDate.indexOf('.')) + 'Z';
                    HistoricCandle lastCandle = candlesHour.get(candlesHour.size() - 1);
                    Date d3 = sdf.parse(timestampToString(lastCandle.getTime()));
                    Date d4 = sdf.parse(currentDate);
                    fillPricesBetweenDates(d3, d4, lastCandle, prices);
                }
                log.info("Ticker: {}, prices size: {}",share.getTicker(),prices.size());
                //System.out.println("Prices size:" + prices.size());
            } catch (Exception e) {
                e.printStackTrace();
            }
            //finalPrices = new ArrayList<>();
            /*if(prices.size() > 60) {
                for (int i = prices.size() - 61; i < prices.size(); i++) {
                    finalPrices.add(prices.get(i));
                }
            }
        return prices;
    }*/

    /*private List<BigDecimal> fillPricesBetweenDates(Date date1, Date date2, HistoricCandle candle, List<BigDecimal> prices)
    {
        try {

            long diff = date2.getTime() - date1.getTime();
            long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
            for(int j=0;j<minutes;j++)
            {
                prices.add(quotationToBigDecimal(candle.getClose()));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return prices;
    }*/




    /*private void printCandle(HistoricCandle candle) {
        var open = quotationToBigDecimal(candle.getOpen());
        var close = quotationToBigDecimal(candle.getClose());
        var high = quotationToBigDecimal(candle.getHigh());
        var low = quotationToBigDecimal(candle.getLow());
        var volume = candle.getVolume();
        var time = timestampToString(candle.getTime());
        log.info(
                "open price: {}, close price: {}, min price for 1 lot: {}, max price for 1 lot: {}, volume " +
                        "of trades in volume: {}, time of the candle: {}",
                open, close, low, high, volume, time);
    }*/

    /*public static void sendMail(String email, String text, String subject)
    {
        try
        {
            String SMTP_AUTH_USER = "ivanovf25@gmail.com";
            String SMTP_AUTH_PWD = "stream422537TAN";

            Properties props = new Properties();

            props.put("mail.transport.protocol", "smtps");
            props.put("mail.smtps.host", SMTP_AUTH_USER);
            props.put("mail.smtps.auth", "true");
            props.put("mail.smtp.sendpartial", "true");

            Session session = Session.getDefaultInstance(props);
            session.setDebug(true);
            Transport transport = session.getTransport();
            transport.connect("smtp.gmail.com", 465, SMTP_AUTH_USER, SMTP_AUTH_PWD);


            MimeMessage message = new MimeMessage(session);
            message.setSubject(subject);
            message.setText(text);
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(email));
            message.setSentDate(new Date());
            transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO));

        }
        catch(Exception e) {e.printStackTrace();}
    }*/


    public static void sendToTelegram(String message) {
        String urlString = "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s";

        //Add Telegram token (given Token is fake)

        String apiToken = MainScenario.telegram_token;

        //Add chatId (given chatId is fake)
        String chatId = MainScenario.chat_id;
        String text = message;

        urlString = String.format(urlString, apiToken, chatId, text);

        try {
            URL url = new URL(urlString);
            URLConnection conn = url.openConnection();
            InputStream is = new BufferedInputStream(conn.getInputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
