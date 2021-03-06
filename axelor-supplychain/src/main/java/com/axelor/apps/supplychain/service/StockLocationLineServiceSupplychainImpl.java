/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.supplychain.service;

import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.service.StockLocationLineServiceImpl;
import com.axelor.apps.supplychain.exception.IExceptionMessage;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.servlet.RequestScoped;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RequestScoped
public class StockLocationLineServiceSupplychainImpl extends StockLocationLineServiceImpl {

  @Override
  public StockLocationLine updateLocation(
      StockLocationLine stockLocationLine,
      Unit stockMoveLineUnit,
      Product product,
      BigDecimal qty,
      boolean current,
      boolean future,
      boolean isIncrement,
      LocalDate lastFutureStockMoveDate,
      BigDecimal requestedReservedQty)
      throws AxelorException {

    stockLocationLine =
        super.updateLocation(
            stockLocationLine,
            stockMoveLineUnit,
            product,
            qty,
            current,
            future,
            isIncrement,
            lastFutureStockMoveDate,
            requestedReservedQty);

    UnitConversionService unitConversionService = Beans.get(UnitConversionService.class);
    Unit stockLocationLineUnit = stockLocationLine.getUnit();
    if (stockLocationLineUnit != null && !stockLocationLineUnit.equals(stockMoveLineUnit)) {
      requestedReservedQty =
          unitConversionService.convert(
              stockMoveLineUnit,
              stockLocationLineUnit,
              requestedReservedQty,
              requestedReservedQty.scale(),
              product);
    }

    if (current) {
      if (isIncrement) {
        stockLocationLine.setRequestedReservedQty(
            computeRequestedReservedQty(stockLocationLine).add(requestedReservedQty));
      } else {
        stockLocationLine.setRequestedReservedQty(
            computeRequestedReservedQty(stockLocationLine).subtract(requestedReservedQty));
      }
    }
    if (future) {
      if (isIncrement) {
        stockLocationLine.setRequestedReservedQty(
            computeRequestedReservedQty(stockLocationLine).subtract(requestedReservedQty));
      } else {
        stockLocationLine.setRequestedReservedQty(
            computeRequestedReservedQty(stockLocationLine).add(requestedReservedQty));
      }
      stockLocationLine.setLastFutureStockMoveDate(lastFutureStockMoveDate);
    }
    return stockLocationLine;
  }

  @Override
  public void checkIfEnoughStock(StockLocation stockLocation, Product product, BigDecimal qty)
      throws AxelorException {
    super.checkIfEnoughStock(stockLocation, product, qty);

    if (Beans.get(AppSupplychainService.class).getAppSupplychain().getManageStockReservation()
        && product.getStockManaged()) {
      StockLocationLine stockLocationLine = this.getStockLocationLine(stockLocation, product);
      if (stockLocationLine != null
          && stockLocationLine
                  .getCurrentQty()
                  .subtract(stockLocationLine.getReservedQty())
                  .compareTo(qty)
              < 0) {
        throw new AxelorException(
            stockLocationLine,
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            I18n.get(IExceptionMessage.LOCATION_LINE_RESERVED_QTY),
            stockLocationLine.getProduct().getName(),
            stockLocationLine.getProduct().getCode());
      }
    }
  }

  @Override
  public BigDecimal getAvailableQty(StockLocation stockLocation, Product product) {
    StockLocationLine stockLocationLine = getStockLocationLine(stockLocation, product);
    BigDecimal availableQty = BigDecimal.ZERO;
    if (stockLocationLine != null) {
      availableQty = stockLocationLine.getCurrentQty().subtract(stockLocationLine.getReservedQty());
    }
    return availableQty;
  }

  @Override
  public StockLocationLine updateLocationFromProduct(
      StockLocationLine stockLocationLine, Product product) throws AxelorException {
    stockLocationLine = super.updateLocationFromProduct(stockLocationLine, product);
    stockLocationLine.setRequestedReservedQty(computeRequestedReservedQty(stockLocationLine));
    return stockLocationLine;
  }

  protected BigDecimal computeRequestedReservedQty(StockLocationLine stockLocationLine)
      throws AxelorException {
    // requested reserved qty is the sum of the qties in planned stock move lines.

    UnitConversionService unitConversionService = Beans.get(UnitConversionService.class);
    Product product = stockLocationLine.getProduct();

    BigDecimal requestedReservedQty = BigDecimal.ZERO;

    // compute from stock move lines
    List<StockMoveLine> stockMoveLineList = findOutgoingPlannedStockMoveLines(stockLocationLine);

    for (StockMoveLine stockMoveLine : stockMoveLineList) {
      BigDecimal qtyToAdd =
          unitConversionService.convert(
              stockMoveLine.getUnit(),
              stockLocationLine.getUnit(),
              stockMoveLine.getRequestedReservedQty(),
              stockMoveLine.getRequestedReservedQty().scale(),
              product);
      requestedReservedQty = requestedReservedQty.add(qtyToAdd);
    }

    return requestedReservedQty;
  }
}
