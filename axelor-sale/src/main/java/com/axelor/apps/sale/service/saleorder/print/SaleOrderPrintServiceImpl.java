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
package com.axelor.apps.sale.service.saleorder.print;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.apps.sale.db.SaleOrder;
import com.axelor.apps.sale.exception.IExceptionMessage;
import com.axelor.apps.sale.report.IReport;
import com.axelor.apps.sale.service.saleorder.SaleOrderService;
import com.axelor.apps.tool.ModelTool;
import com.axelor.apps.tool.StringTool;
import com.axelor.apps.tool.ThrowConsumer;
import com.axelor.apps.tool.file.PdfTool;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

public class SaleOrderPrintServiceImpl implements SaleOrderPrintService {

  @Inject SaleOrderService saleOrderService;

  @Override
  public String printSaleOrder(SaleOrder saleOrder, boolean proforma, String format)
      throws AxelorException, IOException {
    String fileName = getSaleOrderFilesName(Collections.singletonList(saleOrder), format);
    return PdfTool.getFileLinkFromPdfFile(print(saleOrder, proforma, format), fileName);
  }

  @Override
  public String printSaleOrders(List<Long> ids) throws AxelorException, IOException {
    List<File> printedSaleOrders = new ArrayList<>();
    List<SaleOrder> orders = new LinkedList<>();
    for(Long id : ids) {
      SaleOrder o = JPA.find(SaleOrder.class, id);
      if(o == null) continue;
      orders.add(o);
      printedSaleOrders.add(print(o, false, ReportSettings.FORMAT_PDF));
    }
    String fileName = getSaleOrderFilesName(orders, ReportSettings.FORMAT_PDF);
    JPA.clear();
    return PdfTool.mergePdfToFileLink(printedSaleOrders, fileName);
  }

  public File print(SaleOrder saleOrder, boolean proforma, String format) throws AxelorException {
    ReportSettings reportSettings = prepareReportSettings(saleOrder, proforma, format);
    File printedOrder = reportSettings.generate().getFile();
    MetaFile additionalFile = saleOrder.getCompany().getSaleConfig().getSaleOrderAdditionalFile();
    if (additionalFile != null) {
      try {
        return PdfTool.mergePdf(
            Arrays.asList(printedOrder, MetaFiles.getPath(additionalFile).toFile()));
      } catch (IOException ioe) {
        // Ignore
      }
    }
    return printedOrder;
  }

  @Override
  public ReportSettings prepareReportSettings(SaleOrder saleOrder, boolean proforma, String format)
      throws AxelorException {

    if (saleOrder.getPrintingSettings() == null) {
      if (saleOrder.getCompany().getPrintingSettings() != null) {
        saleOrder.setPrintingSettings(saleOrder.getCompany().getPrintingSettings());
      } else {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_MISSING_FIELD,
            String.format(
                I18n.get(IExceptionMessage.SALE_ORDER_MISSING_PRINTING_SETTINGS),
                saleOrder.getSaleOrderSeq()),
            saleOrder);
      }
    }
    String locale = ReportSettings.getPrintingLocale(saleOrder.getClientPartner());

    String title = saleOrderService.getFileName(saleOrder);

    ReportSettings reportSetting =
        ReportFactory.createReport(
            "saleSaleOrder", saleOrder.getCompany(), IReport.SALES_ORDER, title + " - ${date}");

    return reportSetting
        .addParam("SaleOrderId", saleOrder.getId())
        .addParam("Locale", locale)
        .addParam("ProformaInvoice", proforma)
        .addParam("HeaderHeight", saleOrder.getPrintingSettings().getPdfHeaderHeight())
        .addParam("FooterHeight", saleOrder.getPrintingSettings().getPdfFooterHeight())
        .addFormat(format);
  }

  /**
   * Return the name for the printed sale order.
   *
   * @param orders Printed orders.
   */
  protected String getSaleOrderFilesName(List<SaleOrder> orders, String format) {
    if(orders.size() == 1) {
      SaleOrder order = orders.get(0);
      String formatString;
      if(order.getStatusSelect() < 3) {
        formatString = I18n.get("SaleOrder.quotationTitle");
      } else {
        formatString = I18n.get("SaleOrder.orderTitle");
      }
      return StringTool.getFilename(
              MessageFormat.format(formatString, order.getSaleOrderSeq(), order.getVersionNumber() != null && order.getVersionNumber() > 1 ? "-V" + order.getVersionNumber() : "")) + "." + format;
    }

    if(orders.size() > 10) {
      return String.format(I18n.get("%d sale orders"), orders.size())
          + "-"
          + Beans.get(AppBaseService.class).getTodayDate().format(DateTimeFormatter.BASIC_ISO_DATE)
          + "."
          + format;
    }

    List<String> refs = new LinkedList<>();
    for(SaleOrder order : orders) {
      refs.add(order.getSaleOrderSeq());
    }
    return I18n.get("Sale orders")
        + "-"
        + StringUtils.join("-", refs)
        + "."
        + format;
  }
}
