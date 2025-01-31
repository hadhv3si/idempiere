/******************************************************************************
 * Copyright (C) 2009 Low Heng Sin                                            *
 * Copyright (C) 2009 Idalica Corporation                                     *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *                                                                            *
 *  Contributors:                                                             *
 *    Carlos Ruiz - GlobalQSS:                                                *
 *      FR 3132033 - Make payment export class configurable per bank
 *    Markus Bozem:  IDEMPIERE-1546 / IDEMPIERE-3286        				  *
 *****************************************************************************/
package org.compiere.apps.form;

import static org.compiere.model.SystemIDs.REFERENCE_PAYMENTRULE;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.adempiere.base.IPaymentExporterFactory;
import org.adempiere.base.Service;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MLookupInfo;
import org.compiere.model.MPaySelectionCheck;
import org.compiere.model.MPaymentBatch;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Language;
import org.compiere.util.PaymentExport;
import org.compiere.util.ValueNamePair;

public class PayPrint {

	/**	Window No			*/
	public int         	m_WindowNo = 0;
	/**	Used Bank Account	*/
	public int				m_C_BankAccount_ID = -1;
	/**	Export Class for Bank Account	*/
	public String			m_PaymentExportClass = null;
	/**	Payment Selection	*/
	public int         		m_C_PaySelection_ID = 0;

	/** Payment Information */
	public MPaySelectionCheck[]     m_checks = null;
	/** Payment Batch		*/
	public MPaymentBatch	m_batch = null; 
	/**	Logger			*/
	public static CLogger log = CLogger.getCLogger(PayPrint.class);
	
	public String bank;
	public String currency;
	public BigDecimal balance;
	protected PaymentExport m_PaymentExport;
	
	/**
	 *  PaySelect changed - load Bank
	 */
	public void loadPaySelectInfo(int C_PaySelection_ID)
	{
		//  load Banks from PaySelectLine
		m_C_BankAccount_ID = -1;
		String sql = "SELECT ps.C_BankAccount_ID, b.Name || ' ' || ba.AccountNo,"	//	1..2
			+ " c.ISO_Code, CurrentBalance, ba.PaymentExportClass "					//	3..5
			+ "FROM C_PaySelection ps"
			+ " INNER JOIN C_BankAccount ba ON (ps.C_BankAccount_ID=ba.C_BankAccount_ID)"
			+ " INNER JOIN C_Bank b ON (ba.C_Bank_ID=b.C_Bank_ID)"
			+ " INNER JOIN C_Currency c ON (ba.C_Currency_ID=c.C_Currency_ID) "
			+ "WHERE ps.C_PaySelection_ID=? AND ps.Processed='Y' AND ba.IsActive='Y'";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, C_PaySelection_ID);
			rs = pstmt.executeQuery();
			if (rs.next())
			{
				m_C_BankAccount_ID = rs.getInt(1);
				bank = rs.getString(2);
				currency = rs.getString(3);
				balance = rs.getBigDecimal(4);
				m_PaymentExportClass = rs.getString(5);
			}
			else
			{
				m_C_BankAccount_ID = -1;
				bank = "";
				currency = "";
				balance = Env.ZERO;
				m_PaymentExportClass = null;
				log.log(Level.SEVERE, "No active BankAccount for C_PaySelection_ID=" + C_PaySelection_ID);
			}
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, sql, e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}
	}   //  loadPaySelectInfo

	/**
	 *  Bank changed - load PaymentRule
	 */
	public ArrayList<ValueNamePair> loadPaymentRule(int C_PaySelection_ID)
	{
		ArrayList<ValueNamePair> data = new ArrayList<ValueNamePair>();

		// load PaymentRule for Bank
		int AD_Reference_ID = REFERENCE_PAYMENTRULE;  //  MLookupInfo.getAD_Reference_ID("All_Payment Rule");
		Language language = Language.getLanguage(Env.getAD_Language(Env.getCtx()));
		MLookupInfo info = MLookupFactory.getLookup_List(language, AD_Reference_ID);
		String sql = info.Query.substring(0, info.Query.indexOf(" ORDER BY"))
			+ " AND " + info.KeyColumn
			+ " IN (SELECT PaymentRule FROM C_PaySelectionCheck WHERE C_PaySelection_ID=?) "
			+ info.Query.substring(info.Query.indexOf(" ORDER BY"));
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, C_PaySelection_ID);
			rs = pstmt.executeQuery();
			//
			while (rs.next())
			{
				ValueNamePair pp = new ValueNamePair(rs.getString(2), rs.getString(3));
				data.add(pp);
			}
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, sql, e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}
		
		if (data.size() == 0)
			if (log.isLoggable(Level.CONFIG)) log.config("PaySel=" + C_PaySelection_ID + ", BAcct=" + m_C_BankAccount_ID + " - " + sql);
		
		return data;
	}   //  loadPaymentRule
	
	public String noPayments;
	public Integer documentNo;
	public Double sumPayments;
	public Integer printFormatId;

	/**
	 *  PaymentRule changed - load DocumentNo, NoPayments,
	 *  enable/disable EFT, Print
	 */
	public String loadPaymentRuleInfo(int C_PaySelection_ID, String PaymentRule)
	{
		String msg = null;
		
		String sql = "SELECT COUNT(*),SUM(payamt) "
			+ "FROM C_PaySelectionCheck "
			+ "WHERE C_PaySelection_ID=? AND PaymentRule=?";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, C_PaySelection_ID);
			pstmt.setString(2, PaymentRule);
			rs = pstmt.executeQuery();
			//
			if (rs.next())
			{
				noPayments = String.valueOf(rs.getInt(1));
				sumPayments = rs.getDouble(2);
			}   
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, sql, e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}

		printFormatId = null;
		documentNo = null;
		
		//  DocumentNo
		sql = "SELECT CurrentNext, Check_PrintFormat_ID "
			+ "FROM C_BankAccountDoc "
			+ "WHERE C_BankAccount_ID=? AND PaymentRule=? AND IsActive='Y'";
		try
		{
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, m_C_BankAccount_ID);
			pstmt.setString(2, PaymentRule);
			rs = pstmt.executeQuery();
			//
			if (rs.next())
			{
				documentNo = Integer.valueOf(rs.getInt(1));
				printFormatId = Integer.valueOf(rs.getInt(2));
			}
			else
			{
				log.log(Level.SEVERE, "VPayPrint.loadPaymentRuleInfo - No active BankAccountDoc for C_BankAccount_ID="
					+ m_C_BankAccount_ID + " AND PaymentRule=" + PaymentRule);
				msg = "VPayPrintNoDoc";
			}
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, sql, e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}
		
		return msg;
	}   //  loadPaymentRuleInfo
	
	protected int loadPaymentExportClass (StringBuffer err)
	{
		m_PaymentExport = null ;
		
		if (m_PaymentExportClass == null || m_PaymentExportClass.trim().length() == 0) {
			m_PaymentExportClass = "org.compiere.util.GenericPaymentExport";
		}
		try
		{
			List<IPaymentExporterFactory> factories = Service.locator().list(IPaymentExporterFactory.class).getServices();
			if (factories != null && !factories.isEmpty()) {
				for(IPaymentExporterFactory factory : factories) {
					m_PaymentExport = factory.newPaymentExporterInstance(m_PaymentExportClass);
					if (m_PaymentExport != null)
						break;
				}
			}
			
			if (m_PaymentExport == null)
			{
				Class<?> clazz = Class.forName (m_PaymentExportClass);
				m_PaymentExport = (PaymentExport)clazz.getDeclaredConstructor().newInstance();
			}
			
		}
		catch (ClassNotFoundException e)
		{
			if (err!=null)
			{
				err.append("No custom PaymentExport class " + m_PaymentExportClass + " - " + e.toString());
				log.log(Level.SEVERE, err.toString(), e);
			}
			return -1;
		}
		catch (Exception e)
		{
			if (err!=null)
			{
				err.append("Error in " + m_PaymentExportClass + " check log, " + e.toString());
				log.log(Level.SEVERE, err.toString(), e);
			}
			return -1;
		}
		return 0 ;
	} // loadPaymentExportClass
}
