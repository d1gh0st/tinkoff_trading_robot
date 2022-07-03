package tinkoff_trading_robot.classes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.models.Position;
import tinkoff_trading_robot.MainScenario;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLConnection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static ru.tinkoff.piapi.core.utils.DateUtils.timestampToString;
import static ru.tinkoff.piapi.core.utils.MapperUtils.quotationToBigDecimal;

public class Strategy {

    static final Logger log = LoggerFactory.getLogger(Strategy.class);
    private ApiMethods apiMethods;
    private InvestApi api;
    private List<Share> shares;
    int counter;
    Map<String,BigDecimal> first_level_buy;
    Map<String,BigDecimal> second_level_buy;
    Map<String,BigDecimal> third_level_buy;
    Map<String,BigDecimal> first_level_sell;
    Map<String,BigDecimal> second_level_sell;
    Map<String,BigDecimal> third_level_sell;
    Map<String,List<BigDecimal>> all_prices;
    Map<String,List<Integer>> date_intervals;
    List<Share> trends;
    List<BigDecimal> trends_SMA;
    List<BigDecimal> trends_last_price;
    int trends_counter;
    List<String> trend_changed_percent;
    double border = 0.5;

    public Strategy(InvestApi investApi, String account)
    {
        trends_last_price = new ArrayList<>();
        trends_SMA = new ArrayList<>();
        date_intervals = new HashMap<>();
        trend_changed_percent = new ArrayList<>();
        trends_counter = 0;
        all_prices = new HashMap<>();
        api = investApi;
        apiMethods = new ApiMethods(api, account);
        shares = downloadShares();
        /*shares = apiMethods.GetAllShares();
        for(Share share : shares)
        {
            if(share.getTicker().equals("SPCE"))
            {
                BuyShare(share);
                break;
            }
        }*/
        int local_counter = 0;
        log.info("Loading prices...");
        int c = 0;
        if(shares.size() > 0 && (MainScenario.mode.equals("all") ||  MainScenario.mode.equals("buy") ||  MainScenario.mode.equals("test")))
        {

            for(Share share : shares)
            {
                if(local_counter > 200)
                {
                    c++;
                    log.info("Downloaded {} from {}",c*200, shares.size());
                    local_counter = 0;
                    try
                    {
                        Thread.sleep(60000);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                MyShare myShare = apiMethods.getDaysPrices(share);
                List<BigDecimal> prices = myShare.getPrices();
                List<Integer> date_interval = myShare.getIntervals();
                all_prices.put(share.getTicker(),prices);
                date_intervals.put(share.getTicker(),date_interval);
                local_counter++;
            }
            try
            {
                Thread.sleep(60000);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        counter = 0;
        first_level_buy = new HashMap<>();
        second_level_buy = new HashMap<>();
        third_level_buy = new HashMap<>();
        first_level_sell = new HashMap<>();
        second_level_sell = new HashMap<>();
        third_level_sell = new HashMap<>();
        trends = new ArrayList<>();
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

    public void PrintOperations()
    {
        try {
            apiMethods.getOperations(api);
        }
        catch (Exception e)
        {
            e.printStackTrace();
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
            if(buy_price.doubleValue() < position.getAveragePositionPrice().getValue().doubleValue())
            {
                log.info("Stop order price is lower than position price --> calculating profit from position price");
                buy_price = position.getAveragePositionPrice().getValue();
            }
            BigDecimal profit = current_price.subtract(buy_price);

            log.info("Profit from stop order price: {}", profit);
            BigDecimal profit_percent = (current_price.multiply(BigDecimal.valueOf(100)).divide(buy_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Profit percent: {}", profit_percent);
            if (profit_percent.doubleValue() > border) {
                log.info("Profit more than " + border + "! ---------------->");
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
            if (profit_percent.doubleValue() > border) {
                log.info("Profit more than " + border + "! ---------------->");
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
            if (profit_percent.doubleValue() > border) {
                log.info("Profit percent more than " + border + "! Setup stop-market order ---------------->");
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
            if (profit_percent.doubleValue() > border) {
                BigDecimal price = apiMethods.getCurrentBuyPrice(position.getFigi());
                log.info("Profit percent more than " + border + "! Setup stop-market order with price {} ---------------->", price);
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
            BigDecimal bid = apiMethods.getCurrentBuyPrice(position.getFigi());
            BigDecimal ask = apiMethods.getCurrentSellPrice(position.getFigi());
            if(bid != null && ask != null) {
                BigDecimal spred = (ask.subtract(bid)).divide(position.getAveragePositionPrice().getValue(), 9, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                log.info("Spred % - {}", spred);
            }
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


    public void CheckTrendThirdLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = third_level_buy.get(ticker);
        if(prevBuyPrice != null)
            log.info("Third level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            //if(third_level_buy.get(ticker) == null) {
                log.info("Third level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}", prevBuyPrice, price, ticker);
                sendToTelegram("Third level: Trend confirmed! Buy! Ticker: " + ticker);
                third_level_buy.put(ticker, price);
            //}
            //else second_level_buy.remove(ticker);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() < prevBuyPrice.doubleValue())
        {
            third_level_buy.remove(ticker);
            first_level_buy.remove(ticker);
            second_level_buy.remove(ticker);
            //trends.remove(ticker);
        }
        BigDecimal prevSellPrice = third_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("Third level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            //if(third_level_sell.get(ticker) == null) {
                log.info("Third level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}", prevSellPrice, price, ticker);
                sendToTelegram("Third level: Trend confirmed! Sell! Ticker: " + ticker);
                third_level_sell.put(ticker, price);
            //}
            //else second_level_sell.remove(ticker);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else if(price.doubleValue() > prevSellPrice.doubleValue())
        {
            third_level_sell.remove(ticker);
            first_level_sell.remove(ticker);
            second_level_sell.remove(ticker);
            //trends.remove(ticker);
        }
    }


    public void CheckTrendSecondLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = second_level_buy.get(ticker);
        if(prevBuyPrice != null)
            log.info("Second level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            if(third_level_buy.get(ticker) == null) {
                log.info("Second level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}", prevBuyPrice, price, ticker);
                //sendToTelegram("Second level: Trend confirmed! Buy! Ticker: " + ticker);
                third_level_buy.put(ticker, price);
            }
            else second_level_buy.remove(ticker);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() <= prevBuyPrice.doubleValue())
        {
            first_level_buy.remove(ticker);
            second_level_buy.remove(ticker);
            //trends.remove(ticker);
        }
        BigDecimal prevSellPrice = second_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("Second level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            if(third_level_sell.get(ticker) == null) {
                log.info("Second level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}", prevSellPrice, price, ticker);
                //sendToTelegram("Second level: Trend confirmed! Sell! Ticker: " + ticker);
                third_level_sell.put(ticker, price);
            }
            else second_level_sell.remove(ticker);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else if(price.doubleValue() >= prevSellPrice.doubleValue())
        {
            first_level_sell.remove(ticker);
            second_level_sell.remove(ticker);
            //trends.remove(ticker);
        }
    }

    public void CheckTrendFirstLevel(BigDecimal price, String ticker)
    {
        BigDecimal prevBuyPrice = first_level_buy.get(ticker);
        if(prevBuyPrice != null)
        log.info("First level: Checking buy sma's, prev price: {}, current price: {}, ticker: {}",prevBuyPrice,price,ticker);
        if(prevBuyPrice != null && price.doubleValue() > prevBuyPrice.doubleValue())
        {
            if(second_level_buy.get(ticker) == null) {
                log.info("First level: Trend confirmed! Buy! prev price: {}, current price: {}, ticker: {}", prevBuyPrice, price, ticker);
                //sendToTelegram("First level: Trend confirmed! Buy! Ticker: " + ticker);
                second_level_buy.put(ticker, price);
            }
            else first_level_buy.remove(ticker);
        }
        else if(prevBuyPrice == null)
        {
            //
        }
        else if(price.doubleValue() <= prevBuyPrice.doubleValue())
        {
            first_level_buy.remove(ticker);
            //trends.remove(ticker);
        }
        BigDecimal prevSellPrice = first_level_sell.get(ticker);
        if(prevSellPrice != null)
            log.info("First level: Checking sell sma's, prev price: {}, current price: {}, ticker: {}",prevSellPrice,price,ticker);
        if(prevSellPrice != null && price.doubleValue() < prevSellPrice.doubleValue())
        {
            if(second_level_sell.get(ticker) == null) {
                log.info("First level: Trend confirmed! Sell! prev price: {}, current price: {}, ticker: {}", prevSellPrice, price, ticker);
                //sendToTelegram("First level: Trend confirmed! Sell! Ticker: " + ticker);
                second_level_sell.put(ticker, price);
            }
            else first_level_sell.remove(ticker);
        }
        else if(prevSellPrice == null)
        {
            //
        }
        else  if(price.doubleValue() >= prevSellPrice.doubleValue())
        {
            first_level_sell.remove(ticker);
            //trends.remove(ticker);
        }
    }

    private void BuyShare(Share share)
    {
        List<Position> positions = apiMethods.GetShares();
        for (Position position : positions)
        {
            if(position.getFigi().equals(share.getFigi())) return;
        }
        BigDecimal price = apiMethods.getCurrentBuyPrice(share.getFigi());
        BigDecimal consideration = price.multiply(BigDecimal.valueOf(share.getLot()));
        boolean success = apiMethods.CheckAvailableMoney(share.getCurrency(),consideration.doubleValue());
        BigDecimal bid = apiMethods.getCurrentBuyPrice(share.getFigi());
        BigDecimal ask = apiMethods.getCurrentSellPrice(share.getFigi());
        BigDecimal spred = BigDecimal.valueOf(0);
        boolean spread_check = false;
        if(bid != null && ask != null) {
            spred = (ask.subtract(bid)).divide(price, 9, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            log.info("Ticker - {}, Spred % - {}",share.getTicker(), spred);
            spread_check = true;
        }
        BigDecimal stop_price = price.multiply(BigDecimal.valueOf(0.02));
        stop_price = price.subtract(stop_price);
        if(success && spread_check)
        {
            apiMethods.BuyShareByMarketPrice(share.getFigi());
            apiMethods.SetupStopMarket(api,share.getFigi(),stop_price,share.getLot());
        }
    }


    public void CheckLinearRegression2(String currency)
    {
        log.info("Check Linear Regression");
        Share testShare = null;
        int counter = 0;
        int border = 100;
        List<Share> allShares;
        allShares = apiMethods.GetAllShares();
        List<Share> FilteredAllShares = new ArrayList<>();


        List<String> tickers = new ArrayList<>();
        try {
            File file = new File("D:/tinkoff_trading_robot/.idea/tickers.txt");
            FileReader fr = new FileReader(file);
            BufferedReader reader = new BufferedReader(fr);
            String line = reader.readLine();
            while (line != null) {
                tickers.add(line);
                line = reader.readLine();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        for(Share share : allShares)
        {
            for(String ticker : tickers) {
                if (share.getCurrency().equals(currency) && share.getTicker().equals(ticker)) FilteredAllShares.add(share);
            }
        }
        //log.info("Tickers ready. size - {}", FilteredAllShares.size());
        //int time_counter = 0;
        //Instant start = Instant.now().minus(1, ChronoUnit.DAYS);
        //Instant end = Instant.now();
        for (Share share : FilteredAllShares) {



            //while (size<100) {
            /*long vol = 0;
                List<HistoricCandle> cand = api.getMarketDataService()
                        .getCandlesSync(share.getFigi(), start, end,
                                CandleInterval.CANDLE_INTERVAL_DAY);
                if(cand.size() > 0) vol = cand.get(0).getVolume();
                log.info("Ticker - {}, volume - {}", share.getTicker(), vol);
                if(vol >= 0) continue;*/
            //log.info("Ticker - {}", share.getTicker());
            //time_counter++;

            /*try {
                Thread.sleep(100);
                time_counter++;
                //counter = 0;
                ///log.info("Waiting..");
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }*/
            /*if(time_counter > 5)
            {
                /*try
                {
                    Thread.sleep(10000);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                time_counter = 0;
                log.info("LRI Heartbeat");
            }*/
            /*if(share.getTicker().equals("KDP"))
            {
                apiMethods.getPrices(share);
                log.info("test - {}",share.getOtcFlag());

            }
            else continue;*/

            testShare = share;

            List<BigDecimal> prices = new ArrayList<>();
            List<HistoricCandle> candles = new ArrayList<>();
            if (testShare != null)
                candles = apiMethods.getPrices(testShare);

            long volume = 0;
            for(HistoricCandle candle : candles)
            {
                volume += candle.getVolume();
                prices.add(quotationToBigDecimal(candle.getClose()));
            }



            if(counter > border)
            {
                log.info("Checked {} from {}",counter,FilteredAllShares.size());
                border+=100;
            }
            BigDecimal price = apiMethods.getLastPrice(testShare);
            counter++;
            //if(testShare.getTicker().equals("SPCE")) log.info("SPCE volume - {}",volume);
            /*if(volume < 100000 && testShare.getCurrency().equals("RUB"))
            {
                //log.debug("ticker: {}, low liquidity",share.getTicker());
                continue;
            }
            if(volume < 20000000 && !testShare.getCurrency().equals("RUB"))
            {
                continue;
            }*/
            //if(prices.size()  == 0) continue;


            //result.add(share);
            /*if (share.getTicker().equals("AMZN") ) {
                testShare = share;
                break;
            }*/





            BigDecimal sumX = BigDecimal.valueOf(0);
            BigDecimal sumY = BigDecimal.valueOf(0);
            BigDecimal sumXSqr = BigDecimal.valueOf(0);
            BigDecimal sumXY = BigDecimal.valueOf(0);
            //log.info("ticker - {}", testShare.getTicker());
            for (int i = 0; i < prices.size(); i++) {
                BigDecimal val = prices.get(i);
                //log.info("price - {}, time - {}",val,timestampToString(candles.get(i).getTime()));
                BigDecimal per = BigDecimal.valueOf(i).add(BigDecimal.valueOf(1));
                sumX = sumX.add(per);
                sumY = sumY.add(val);
                sumXSqr = sumXSqr.add(per.multiply(per));
                sumXY = sumXY.add(val.multiply(per));
            }
            //slope = (length * sumXY - sumX * sumY) / (length * sumXSqr - sumX * sumX)
            //average = sumY / length
            //intercept = average - slope * sumX / length + slope
            BigDecimal s1 = (BigDecimal.valueOf(100).multiply(sumXY)).subtract((sumX.multiply(sumY)));
            BigDecimal s2 = (BigDecimal.valueOf(100).multiply(sumXSqr)).subtract((sumX.multiply(sumX)));
            BigDecimal slope = s1.divide(s2, 9, RoundingMode.HALF_UP);
            BigDecimal average = sumY.divide(BigDecimal.valueOf(100), 9, RoundingMode.HALF_UP);
            BigDecimal i1 = average.subtract((slope.multiply(sumX)));
            BigDecimal i2 = BigDecimal.valueOf(100).add(slope);
            //log.info("sumX - {}", sumX);
            BigDecimal intercept = average.subtract(slope.multiply(sumX).divide(BigDecimal.valueOf(100), 9, RoundingMode.HALF_UP).add(slope));

            log.info("slope - {}", slope);
            log.info("average - {}", average);
            log.info("intercept - {}", intercept);

            BigDecimal result = intercept.add(slope.multiply(BigDecimal.valueOf(99)));
            //log.info("result - {}", result);
            SMA(testShare, prices, price);
            BigDecimal uppBorder = result.add(result.multiply(BigDecimal.valueOf(0.02)));
            BigDecimal bottomBorder = result.subtract(result.multiply(BigDecimal.valueOf(0.02)));
            if(price.doubleValue() < uppBorder.doubleValue() && price.doubleValue() > result.doubleValue())
            {
                BigDecimal bid = apiMethods.getCurrentBuyPrice(share.getFigi());
                BigDecimal ask = apiMethods.getCurrentSellPrice(share.getFigi());
                BigDecimal spred = BigDecimal.valueOf(0);
                if(bid != null && ask != null) {
                    spred = (ask.subtract(bid)).divide(price, 9, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    log.info("Ticker - {}, Spred % - {}",share.getTicker(), spred);
                }
                if ((price.doubleValue() > 2 && spred.doubleValue() < 0.5) || (price.doubleValue() < 2 && spred.doubleValue() < 2)) {
                    SMA(testShare, prices, price);
                    log.info("volume - {}", volume);
                    log.info("result - {}", result);
                    log.info("LRI!!! Buy Ticker: {}", testShare.getTicker());
                }
            }
            else if(price.doubleValue() < result.doubleValue() && bottomBorder.doubleValue() < price.doubleValue())
            {
                BigDecimal bid = apiMethods.getCurrentBuyPrice(share.getFigi());
                BigDecimal ask = apiMethods.getCurrentSellPrice(share.getFigi());
                BigDecimal spred = BigDecimal.valueOf(0);
                if(bid != null && ask != null) {
                    spred = (ask.subtract(bid)).divide(price, 9, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    log.info("Ticker - {}, Spred % - {}",share.getTicker(), spred);
                }
                if ((price.doubleValue() > 2 && spred.doubleValue() < 0.5) || (price.doubleValue() < 2 && spred.doubleValue() < 2)) {
                    SMA(testShare, prices, price);
                    log.info("volume - {}", volume);
                    log.info("result - {}", result);
                    log.info("LRI!!! Sell Ticker: {}", testShare.getTicker());
                }
            }

            //    upDev = 0.0
            //    dnDev = 0.0
            //    stdDevAcc = 0.0
            //    dsxx = 0.0
            //    dsyy = 0.0
            //    dsxy = 0.0
            //    periods = length - 1
            //    daY = intercept + slope * periods / 2
            //    val = intercept
            //    for j = 0 to periods by 1
            //        price = high[j] - val
            //        if price > upDev
            //            upDev := price
            //        price := val - low[j]
            //        if price > dnDev
            //            dnDev := price
            //        price := source[j]
            //        dxt = price - average
            //        dyt = val - daY
            //        price -= val
            //        stdDevAcc += price * price
            //        dsxx += dxt * dxt
            //        dsyy += dyt * dyt
            //        dsxy += dxt * dyt
            //        val += slope
            //    stdDev = math.sqrt(stdDevAcc / (periods == 0 ? 1 : periods))
            //    pearsonR = dsxx == 0 or dsyy == 0 ? 0 : dsxy / math.sqrt(dsxx * dsyy)

            BigDecimal upDev = BigDecimal.valueOf(0);
            BigDecimal dnDev = BigDecimal.valueOf(0);
            BigDecimal stdDevAcc = BigDecimal.valueOf(0);
            BigDecimal dsxx = BigDecimal.valueOf(0);
            BigDecimal dsyy = BigDecimal.valueOf(0);
            BigDecimal dsxy = BigDecimal.valueOf(0);
            int periods = 99;
            BigDecimal daY = intercept.add(slope.multiply(BigDecimal.valueOf(periods).divide(BigDecimal.valueOf(2),9,RoundingMode.HALF_UP)));
            BigDecimal val = intercept;
            for(int i=0;i<periods;i++)
            {
                BigDecimal p = prices.get(i);

                if(p.doubleValue() > val.doubleValue())
                {
                    BigDecimal pricee = p.subtract(val);
                    if(pricee.doubleValue() > upDev.doubleValue())
                    {
                        upDev = p;
                    }
                }
                if(p.doubleValue() < val.doubleValue())
                {
                    BigDecimal pricee = val.subtract(p);
                    if(pricee.doubleValue() > dnDev.doubleValue())
                    {
                        dnDev = p;
                    }
                }
                BigDecimal dxt = p.subtract(average);
                BigDecimal dyt = val.subtract(daY);
                p = p.subtract(val);
                stdDevAcc = stdDevAcc.add(p.multiply(p));
                dsxx = dsxx.add(dxt.multiply(dxt));
                dsyy = dsyy.add(dyt.multiply(dyt));
                dsxy = dsxy.add(dxt.multiply(dyt));
                val = val.add(slope);
            }
            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(stdDevAcc.divide(BigDecimal.valueOf(periods),9,RoundingMode.HALF_UP).doubleValue()));
            log.info("stdDev - {}",stdDev);
            log.info("upDev - {}",upDev);
            log.info("dnDev - {}",dnDev);
            BigDecimal upperLine = result.add(BigDecimal.valueOf(2).multiply(stdDev));
            BigDecimal bottomLine = result.subtract(BigDecimal.valueOf(2).multiply(stdDev));
            log.info("upperLine - {}",upperLine);
            log.info("bottomLine - {}",bottomLine);

            BigDecimal uppBorderFromBottomLine = bottomLine.add(bottomLine.multiply(BigDecimal.valueOf(0.02)));
            BigDecimal bottomBorderFromUpperLine = upperLine.subtract(upperLine.multiply(BigDecimal.valueOf(0.02)));

            if(price.doubleValue() < uppBorderFromBottomLine.doubleValue() && price.doubleValue() > bottomLine.doubleValue())
            {
                BigDecimal bid = apiMethods.getCurrentBuyPrice(share.getFigi());
                BigDecimal ask = apiMethods.getCurrentSellPrice(share.getFigi());
                BigDecimal spred = BigDecimal.valueOf(0);
                if(bid != null && ask != null) {
                    spred = (ask.subtract(bid)).divide(price, 9, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    log.info("Ticker - {}, Spred % - {}",share.getTicker(), spred);
                }
                if ((price.doubleValue() > 2 && spred.doubleValue() < 0.5) || (price.doubleValue() < 2 && spred.doubleValue() < 2)) {
                    SMA(testShare, prices, price);
                    log.info("volume - {}", volume);
                    log.info("result - {}", result);
                    log.info("LRI!!! Buy Ticker: {}", testShare.getTicker());
                }
            }
            else if(price.doubleValue() < upperLine.doubleValue() && bottomBorderFromUpperLine.doubleValue() < price.doubleValue())
            {
                BigDecimal bid = apiMethods.getCurrentBuyPrice(share.getFigi());
                BigDecimal ask = apiMethods.getCurrentSellPrice(share.getFigi());
                BigDecimal spred = BigDecimal.valueOf(0);
                if(bid != null && ask != null) {
                    spred = (ask.subtract(bid)).divide(price, 9, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    log.info("Ticker - {}, Spred % - {}",share.getTicker(), spred);
                }
                if ((price.doubleValue() > 2 && spred.doubleValue() < 0.5) || (price.doubleValue() < 2 && spred.doubleValue() < 2)) {
                    SMA(testShare, prices, price);
                    log.info("volume - {}", volume);
                    log.info("result - {}", result);
                    log.info("LRI!!! Sell Ticker: {}", testShare.getTicker());
                }
            }

        }
    }

    public void CheckLinearRegression()
    {
        for(Share share : shares)
        {
            List<BigDecimal> prices = all_prices.get(share.getTicker());
            List<Integer> intervals = date_intervals.get(share.getTicker());
            if(prices.size() > 0)
            {
                List<Integer> y_line = new ArrayList<>();
                y_line.add(1);
                int prev_value = 1;
                /*List<BigDecimal> Exy_line = new ArrayList<>();
                List<BigDecimal> Ex_line = new ArrayList<>();
                List<Integer> Ey_line = new ArrayList<>();
                List<BigDecimal> Ex2_line = new ArrayList<>();
                List<Integer> Ey2_line = new ArrayList<>();*/
                BigDecimal Ex = BigDecimal.valueOf(0);
                int Ey = 0;
                BigDecimal Exy = BigDecimal.valueOf(0);
                BigDecimal Ex2 = BigDecimal.valueOf(0);
                int Ey2 = 0;
                BigDecimal high_price = prices.get(0);
                BigDecimal low_price = prices.get(0);
                int high_number = 0;
                int low_number = 0;
                BigDecimal meanSum = BigDecimal.valueOf(0);
                List<BigDecimal> new_prices = new ArrayList<>();
                //boolean deviation_Exist = true;
                //int num = 0;
                //while (num < 1) {
                //    deviation_Exist = false;
                    for (int i = 0; i < prices.size(); i++) {
                        meanSum = meanSum.add(prices.get(0));
                    }

                    BigDecimal mean = ((meanSum.divide(BigDecimal.valueOf(prices.size()), 8, RoundingMode.HALF_UP)));
                    System.out.println("The mean is: " + mean);
                    BigDecimal deviationSum = BigDecimal.valueOf(0);
                    for (int i = 0; i < prices.size(); i++) {
                        deviationSum = deviationSum.add(BigDecimal.valueOf(Math.pow(prices.get(i).subtract(mean).doubleValue(), 2)));
                        //array[i] = (Math.pow((array[i] - mean), 2));
                    }
                /*for (int i = 0; i < size; i++)
                {
                    deviationSum += array[i];
                }*/

                    double variance = ((deviationSum.divide(BigDecimal.valueOf(prices.size()), 8, RoundingMode.HALF_UP))).doubleValue();

                    double standardDeviation = Math.sqrt(variance);
                    //standardDeviation =2;
                    System.out.println("The standard deviation is: " + standardDeviation);
                    /*for (int i = 0; i < prices.size(); i++) {
                        BigDecimal price = prices.get(i);
                        if (price.doubleValue() > mean.doubleValue() + standardDeviation || price.doubleValue() < mean.doubleValue() - standardDeviation) {
                            //deviation_Exist = true;
                        }
                        else new_prices.add(price);
                    }
                    prices.clear();
                    prices.addAll(new_prices);
                    new_prices.clear();*/
                //    if(deviation_Exist)
                //    {
                //        meanSum = BigDecimal.valueOf(0);
                //    }
                //    num++;
                //}
                for(int i=0;i<prices.size();i++)
                {
                    BigDecimal price = prices.get(i);
                    //log.info("Price: {}", price);
                    if(i < prices.size()-1) {
                        int interval = intervals.get(i);
                        prev_value += interval;
                        //if(interval == 0) prev_value++;
                        y_line.add(prev_value);
                    }
                    int y_value = y_line.get(i);
                    BigDecimal xy_value = price.multiply(BigDecimal.valueOf(y_value));
                    BigDecimal x2_value = price.multiply(price);
                    int y2_value = y_value*y_value;
                    Ex = Ex.add(price);
                    Ey = Ey + y_value;
                    Exy = Exy.add(xy_value);
                    Ex2 = Ex2.add(x2_value);
                    Ey2 = Ey2 + y2_value;
                    /*Ex_line.add(Ex);
                    Ey_line.add(Ey);
                    Exy_line.add(Exy);
                    Ex2_line.add(Ex2);
                    Ey2_line.add(Ey2);*/
                }
                int n = prices.size();
                BigDecimal b = (BigDecimal.valueOf(n).multiply(Exy).subtract(Ex.multiply(BigDecimal.valueOf(Ey)))).divide(BigDecimal.valueOf(n).multiply(Ex2).subtract(Ex.multiply(Ex)),8,RoundingMode.HALF_UP);
                BigDecimal a = (BigDecimal.valueOf(Ey).subtract(b.multiply(Ex))).divide(BigDecimal.valueOf(n),8,RoundingMode.HALF_UP);
                BigDecimal x = (BigDecimal.valueOf(n).subtract(a)).divide(b,8,RoundingMode.HALF_UP);
                log.info("Linear Regression: Ticker: {}, a-coefficient: {}, b-coefficient: {}, period size: {}, current point of regression: {}", share.getTicker(),a,b,n,x);
                /*BigDecimal x_low = (BigDecimal.valueOf(low_number).subtract(a)).divide(b,8,RoundingMode.HALF_UP);
                BigDecimal x_high = (BigDecimal.valueOf(high_number).subtract(a)).divide(b,8,RoundingMode.HALF_UP);
                BigDecimal coef_channel_low = x_low.subtract(low_price);
                BigDecimal coef_channel_high = high_price.subtract(x_high);
                BigDecimal coef_channel = (coef_channel_high.add(coef_channel_low)).divide(BigDecimal.valueOf(2),8,RoundingMode.HALF_UP);
                BigDecimal low_point = x.subtract(coef_channel);
                BigDecimal high_point = x.add(coef_channel);
                log.info("Low point: {}, High point: {}",low_point,high_point);*/
                BigDecimal sum = BigDecimal.valueOf(0);
                for(int i=0;i<prices.size();i++)
                {
                    BigDecimal pred = (BigDecimal.valueOf(i).subtract(a)).divide(b,8,RoundingMode.HALF_UP);
                    BigDecimal val = pred.subtract(prices.get(i));
                    sum = sum.add(val.multiply(val));
                }
                BigDecimal average = sum.divide(BigDecimal.valueOf(prices.size()),2,RoundingMode.HALF_UP);
                BigDecimal rmse = BigDecimal.valueOf(Math.sqrt(average.doubleValue())).multiply(BigDecimal.valueOf(2));
                BigDecimal low_point = x.subtract(rmse);
                BigDecimal high_point = x.add(rmse);
                log.info("Low point: {}, High point: {}",low_point,high_point);
                BigDecimal last_price = prices.get(prices.size()-1);
                BigDecimal low = last_price.subtract(last_price.multiply(BigDecimal.valueOf(0.05)));
                BigDecimal high = last_price.add(last_price.multiply(BigDecimal.valueOf(0.05)));
                if(x.doubleValue() > low.doubleValue() && x.doubleValue() < high.doubleValue())
                {
                    log.info("!!! LRI, Ticker {},",share.getTicker());
                }
                if(low_point.doubleValue() > low.doubleValue() && low_point.doubleValue() < high.doubleValue())
                {
                    log.info("!!! LRI, Ticker {},",share.getTicker());
                }
                if(high_point.doubleValue() > low.doubleValue() && high_point.doubleValue() < high.doubleValue())
                {
                    log.info("!!! LRI, Ticker {},",share.getTicker());
                }
            }
        }
    }


    public void CheckTrends()
    {
        List<Share> new_trends = new ArrayList<>();
        List<BigDecimal> new_SMA = new ArrayList<>();
        List<BigDecimal> new_last_price = new ArrayList<>();
        try {
            for(int i=0;i<trends.size();i++)
            {
                Share share = trends.get(i);
                //log.info("Check trends - {}",share.getTicker());
                BigDecimal price = apiMethods.getLastPrice(share);
                BigDecimal SMA = trends_SMA.get(i);
                BigDecimal last_price = trends_last_price.get(i);
                if(SMA.doubleValue() > last_price.doubleValue() && price.doubleValue() > SMA.doubleValue())
                {
                    sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
                            + last_price + ", new_price: {} " + price);
                }
                else if(SMA.doubleValue() < last_price.doubleValue() && price.doubleValue() < SMA.doubleValue())
                {
                    sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
                            + last_price + ", new_price: {} " + price);
                }
                else
                {
                    new_trends.add(share);
                    new_SMA.add(SMA);
                    new_last_price.add(last_price);
                }
            }
            trends = new_trends;
            trends_SMA = new_SMA;
            trends_last_price = new_last_price;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /*public void CheckTrends()
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
                trends_counter++;
                CheckTrendThirdLevel(new_price, ticker);
                CheckTrendSecondLevel(new_price,ticker);
                CheckTrendFirstLevel(new_price,ticker);
            }

        }
    }*/

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
            if(shares.size() != all_prices.size())
            {
                log.info("Update all prices");
                try {
                    Thread.sleep(60000);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }

        //CheckTrends();
        int lastBoundary = counter + 250 - trends_counter;
        if(lastBoundary > shares.size()) lastBoundary = shares.size();
        int sma_count = 0;
        for(int i=counter;i<lastBoundary;i++)
        {
            Share share = shares.get(i);
            //List<BigDecimal> prices = apiMethods.getDaysPrices(share);
            List<BigDecimal> prices = all_prices.get(share.getTicker());
            BigDecimal new_price = apiMethods.getLastPrice(share);
            if(prices != null && prices.size() >= 9 && new_price.doubleValue() != 0) {
                sma_count++;
                SMA(share, prices, new_price);
            }
        }
        log.info("SMA counted: {}",sma_count);
        log.info("SMA buy breakdowns:{}",first_level_buy.size());
        log.info("SMA sell breakdowns:{}",first_level_sell.size());
        log.info("Current range: from {} to {}",counter,lastBoundary);
        counter = lastBoundary;
        log.info("Trend counter: {}", trends_counter);
        trends_counter = 0;
    }


    public List<Share> downloadShares()
    {
        log.info("Download shares");
        List<Share> result = new ArrayList<>();
        try {
            if(MainScenario.mode.equals("test"))
            {
                //result = apiMethods.GetAllShares();
                List<Share> shares = apiMethods.GetAllShares();
                for (Share share : shares) {
                    //result.add(share);
                    if (share.getTicker().equals("GOOG") ) {
                        result.add(share);
                        break;
                    }
                }
            }
            else {
                List<Share> shares = apiMethods.GetAllShares();
                for (Share share : shares) {
                    if (share.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING)
                        result.add(share);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return result;
    }



    private void SMA(Share share, List<BigDecimal> prices, BigDecimal new_price)
    {
        BigDecimal finalPrice = BigDecimal.valueOf(0);
        log.info("Calculating SMA");
        //String p = "";
        for(int i=prices.size()-9;i<prices.size();i++)
        {
            BigDecimal price = prices.get(i);
            //p = p + " " + price.doubleValue();
            finalPrice = finalPrice.add(price);
        }
        //log.info("Prices:{}",p);
        BigDecimal SMA = finalPrice.divide(BigDecimal.valueOf(9),9,RoundingMode.HALF_UP);
        BigDecimal last_price = prices.get(prices.size()-1);
        log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
        log.info("new price: {}",new_price);
        trends.add(share);
        trends_SMA.add(SMA);
        trends_last_price.add(last_price);
        if(SMA.doubleValue() > last_price.doubleValue() && new_price.doubleValue() > SMA.doubleValue())
        {
            log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
            log.info("new price: {}",new_price);
            log.info("Ticker {}, SMA Buy!!!", share.getTicker());
            BigDecimal percent = (new_price.multiply(BigDecimal.valueOf(100)).divide(last_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Price growth percent: {}",percent);
            //sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
            //        + last_price + ", new_price: {} " + new_price + ", percent of growth: " + percent);
            /*if(percent.doubleValue() > border && !trend_changed_percent.contains(share.getTicker())) {
                //third_level_buy.put(share.getTicker(), new_price);
                trend_changed_percent.add(share.getTicker());
                sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
                    + last_price + ", new_price: {} " + new_price + ", percent of growth: " + percent);
            }
            else if(percent.doubleValue() < border)
            {
                trend_changed_percent.remove(share.getTicker());
            }
            first_level_buy.put(share.getTicker(),new_price);
            trends.add(share.getTicker());*/


        }
        if(SMA.doubleValue() < last_price.doubleValue() && new_price.doubleValue() < SMA.doubleValue())
        {
            log.info("Ticker: {}, SMA: {}, last price: {}",share.getTicker(), SMA, last_price);
            log.info("new price: {}",new_price);
            log.info("Ticker {}, SMA Sell!!!", share.getTicker());
            BigDecimal percent = (new_price.multiply(BigDecimal.valueOf(100)).divide(last_price, 2, RoundingMode.HALF_UP)).subtract(BigDecimal.valueOf(100));
            log.info("Price fall percent: {}",percent);
            if(percent.abs().doubleValue() > border && !trend_changed_percent.contains(share.getTicker())) {
                trend_changed_percent.add(share.getTicker());
                //sendToTelegram("Ticker: " + share.getTicker() + ", SMA: " + SMA + ", last price: "
                //    + last_price + ", new_price: {} " + new_price + ", percent of falling: " + percent);
            }
            else if(percent.doubleValue() < border)
            {
                trend_changed_percent.remove(share.getTicker());
            }
            first_level_sell.put(share.getTicker(),new_price);
            //trends.add(share.getTicker());


        }

    }


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
