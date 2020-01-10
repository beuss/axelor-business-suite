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
package com.axelor.apps.account.service.invoice.print;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.report.IReport;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.apps.tool.ModelTool;
import com.axelor.apps.tool.ThrowConsumer;
import com.axelor.apps.tool.file.PdfTool;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Implementation of the service printing invoices. */
@Singleton
public class InvoicePrintServiceImpl implements InvoicePrintService {

  @Inject private InvoiceRepository invoiceRepo;

  @Override
  public String printInvoice(Invoice invoice, boolean forceRefresh)
      throws AxelorException, IOException {
    String fileName = getInvoiceFilesName(false);
    return PdfTool.getFileLinkFromPdfFile(printCopiesToFile(invoice, forceRefresh), fileName);
  }

  @Override
  public File printCopiesToFile(Invoice invoice, boolean forceRefresh)
      throws AxelorException, IOException {
    File file = getPrintedInvoice(invoice, forceRefresh);
    int copyNumber = invoice.getInvoicesCopySelect();
    return PdfTool.printCopiesToFile(file, copyNumber);
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, IOException.class, RuntimeException.class})
  public File getPrintedInvoice(Invoice invoice, boolean forceRefresh) throws AxelorException {

    // if invoice is ventilated (or just validated for advance payment invoices)
    if (invoice.getStatusSelect() == InvoiceRepository.STATUS_VENTILATED
        || (invoice.getOperationSubTypeSelect() == InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE
            && invoice.getStatusSelect() == InvoiceRepository.STATUS_VALIDATED)) {

      // return a previously generated printing if possible
      if (!forceRefresh && invoice.getPrintedPDF() != null) {

        Path path = MetaFiles.getPath(invoice.getPrintedPDF().getFileName());
        return path.toFile();
      } else {

        // generate a new printing
        return printAndSave(invoice);
      }
    } else {
      // invoice is not ventilated (or validated for advance payment invoices) --> generate and
      // don't save
      return print(invoice);
    }
  }

  public File print(Invoice invoice) throws AxelorException {
    return generateInvoice(invoice, prepareReportSettings(invoice));
  }

  public File printAndSave(Invoice invoice) throws AxelorException {

    ReportSettings reportSettings = prepareReportSettings(invoice);
    MetaFile metaFile;

    reportSettings.toAttach(invoice);
    File file = generateInvoice(invoice, reportSettings);

    try {
      MetaFiles metaFiles = Beans.get(MetaFiles.class);
      metaFile = metaFiles.upload(file);
      invoice.setPrintedPDF(metaFile);
      return MetaFiles.getPath(metaFile).toFile();
    } catch (IOException e) {
      throw new AxelorException(
          TraceBackRepository.TYPE_TECHNICAL,
          I18n.get(IExceptionMessage.INVOICE_PRINTING_IO_ERROR) + " " + e.getLocalizedMessage());
    }
  }

  @Override
  public String printInvoices(List<Long> ids) throws IOException, AxelorException {
    List<File> printedInvoices = new ArrayList<>();
    List<String> invalidPrintSettingsInvoiceIds = checkInvalidPrintSettingsInvoices(ids);

    if (invalidPrintSettingsInvoiceIds.size() > 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get(IExceptionMessage.INVOICES_MISSING_PRINTING_SETTINGS),
          invalidPrintSettingsInvoiceIds.toString());
    }

    ModelTool.apply(
        Invoice.class,
        ids,
        new ThrowConsumer<Invoice>() {
          @Override
          public void accept(Invoice invoice) throws Exception {
            printedInvoices.add(printCopiesToFile(invoice, false));
          }
        });

    String fileName = getInvoiceFilesName(true);
    return PdfTool.mergePdfToFileLink(printedInvoices, fileName);
  }

  public List<String> checkInvalidPrintSettingsInvoices(List<Long> ids) {

    List<String> invalidPrintSettingsInvoiceIds = new ArrayList<>();

    for (Long id : ids) {
      Invoice invoice = invoiceRepo.find(id);
      if (invoice.getPrintingSettings() == null) {
        invalidPrintSettingsInvoiceIds.add(invoice.getInvoiceId());
      }
    }
    return invalidPrintSettingsInvoiceIds;
  }

  @Override
  public ReportSettings prepareReportSettings(Invoice invoice) throws AxelorException {

    if (invoice.getPrintingSettings() == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          String.format(
              I18n.get(IExceptionMessage.INVOICE_MISSING_PRINTING_SETTINGS),
              invoice.getInvoiceId()),
          invoice);
    }
    String locale = ReportSettings.getPrintingLocale(invoice.getPartner());

    String title = I18n.get("Invoice");
    if (invoice.getInvoiceId() != null) {
      title += " " + invoice.getInvoiceId();
    }

    ReportSettings reportSetting =
        ReportFactory.createReport(
            "accountInvoice", invoice.getCompany(), IReport.INVOICE, title + " - ${date}");

    return reportSetting
        .addParam("InvoiceId", invoice.getId())
        .addParam("Locale", locale)
        .addParam("HeaderHeight", invoice.getPrintingSettings().getPdfHeaderHeight())
        .addParam("FooterHeight", invoice.getPrintingSettings().getPdfFooterHeight());
  }

  /**
   * Return the name for the printed invoice.
   *
   * @param plural if there is one or multiple invoices.
   */
  protected String getInvoiceFilesName(boolean plural) {

    return I18n.get(plural ? "Invoices" : "Invoice")
        + " - "
        + Beans.get(AppBaseService.class).getTodayDate().format(DateTimeFormatter.BASIC_ISO_DATE)
        + ".pdf";
  }

  /**
   * Generate invoice copy taking care of the additional file configuration.
   *
   * @param invoice Invoice to be printed (must be the same as the one bound to reportSettings)
   * @param reportSettings Report settings used to print invoice, must be ready to generate.
   * @return The file containing the generated invoice, with optional additional file appended.
   * @throws AxelorException If something goes wrong.
   */
  protected File generateInvoice(Invoice invoice, ReportSettings reportSettings)
      throws AxelorException {
    if (invoice.getOperationTypeSelect() != InvoiceRepository.OPERATION_TYPE_CLIENT_SALE
        && invoice.getOperationTypeSelect() != InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND) {
      return reportSettings.generate().getFile();
    }
    File printedInvoice = reportSettings.generate().getFile();
    MetaFile additionalFile =
        invoice.getCompany().getAccountConfig().getSaleInvoiceAdditionalFile();
    if (additionalFile != null) {
      try {
        return PdfTool.mergePdf(
            Arrays.asList(printedInvoice, MetaFiles.getPath(additionalFile).toFile()));
      } catch (IOException ioe) {
        // Ignore
      }
    }
    return printedInvoice;
  }
}
