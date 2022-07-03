package tinkoff_trading_robot.classes;

import java.math.BigDecimal;
import java.util.List;

public class MyShare {
    List<BigDecimal> prices;
    List<Integer> intervals;

    public MyShare(List<BigDecimal> p, List<Integer> i)
    {
        prices = p;
        intervals = i;
    }

    public List<Integer> getIntervals() {
        return intervals;
    }

    public List<BigDecimal> getPrices() {
        return prices;
    }
}
