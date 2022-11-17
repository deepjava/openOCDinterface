package org.deepjava.openOCDInterface;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Map;

import org.deepjava.config.Configuration;
import org.deepjava.config.Parser;
import org.deepjava.config.Register;
import org.deepjava.eclipse.DeepPlugin;
import org.deepjava.eclipse.ui.preferences.PreferenceConstants;
import org.deepjava.host.StdStreams;
import org.deepjava.linker.TargetMemorySegment;
import org.deepjava.strings.HString;
import org.deepjava.target.TargetConnection;
import org.deepjava.target.TargetConnectionException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class OpenOCD extends TargetConnection {

	private static boolean dbg = false;
	private static TargetConnection tc;
	private String hostname;
	private int port;
	private Socket socket;
	private OutputStream out;
	private InputStream in;

	private OpenOCD() {	}
	
	public static TargetConnection getInstance() {
		if (tc != null && !tc.isConnected()) tc = null;
		if (tc == null) {
			if (dbg) StdStreams.vrb.println("[TARGET] OpenOCD: Creating new OpenOCD telnet connection");
			tc = new OpenOCD();
		}
		return tc;
	}
		
	@Override
	public void openConnection() throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] Open connection");	
		try {
			socket = new Socket(hostname, port);
			socket.setSoTimeout(1000);
			out = socket.getOutputStream();
			in = socket.getInputStream();
		} catch (IOException e) {
			if (dbg) StdStreams.vrb.println("[TARGET] no socket connection possible, start OpenOCD");
			String cmd = DeepPlugin.getDefault().getPreferenceStore().getString(PreferenceConstants.DEFAULT_OPENOCD_CMD);
			String opt = DeepPlugin.getDefault().getPreferenceStore().getString(PreferenceConstants.DEFAULT_OPENOCD_OPTIONS);
			if (dbg) StdStreams.vrb.println("[TARGET] cmd: " + cmd);
			if (dbg) StdStreams.vrb.println("[TARGET] options: " + opt);
			try {
				if (System.getProperty("os.name").toLowerCase().indexOf("windows") >= 0) { // is windows system
					String path = cmd.substring(0, cmd.lastIndexOf("bin-x64"));
					File dir = new File(path);
					if (dbg) StdStreams.vrb.println("[TARGET] cmd /c start \"\" \"" + cmd + "\" " + opt);
					Runtime.getRuntime().exec("cmd /c start \"\" \"" + cmd + "\" " + opt, null, dir);
				} else if (System.getProperty("os.name").toLowerCase().indexOf("linux") >= 0) { // is linux system
					String path = cmd.substring(0, cmd.lastIndexOf("openocd"));
					File dir = new File(path);
					if (dbg) StdStreams.vrb.println("[TARGET] " + cmd + opt);
					Runtime.getRuntime().exec(cmd + opt, null, dir);
				}
				socket = new Socket();
				SocketAddress addr = new InetSocketAddress(hostname, port);
				try {
					Thread.currentThread().sleep(3000);
				} catch (InterruptedException e2) {
					// TODO Auto-generated catch block
					e2.printStackTrace();
				}
				socket.connect(addr, 10000);
				socket.setSoTimeout(1000);
				out = socket.getOutputStream();
				in = socket.getInputStream();
				if (dbg) StdStreams.vrb.println("[TARGET] connected");
				try {
					StringBuffer sb = new StringBuffer();
					out.write(("reset halt\r\n").getBytes());		// check if target present
					while (true) {
						int c = in.read();
						sb.append((char)c);
						if (sb.indexOf("not examined yet") >= 0) {
							if (dbg) StdStreams.vrb.println("[TARGET] Target not answering");
							throw new TargetConnectionException("Target not answering");	
						}
						if (sb.indexOf("disabled") >= 0) return;	
					}
				} catch (Exception e1) {
					throw new TargetConnectionException(e1.getMessage(), e1);
				}
			} catch (IOException e1) {
				if (dbg) StdStreams.vrb.println("[TARGET] Cannot connect to OpenOCD server");
				throw new TargetConnectionException("Cannot connect to OpenOCD server", e1);
			}
		} catch (Exception e) {
			if (dbg) StdStreams.vrb.println("[TARGET] Connection failed on " + hostname);
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void setOptions(HString opts) {
		int index = opts.indexOf('_');
		if (index  == -1) {
			StdStreams.err.println("[TARGET] programmeropts need to be in format 'hostname_port'. I.e. 'localhost_4444");
		} else {
			hostname = opts.substring(0, index).toString() ;
			port = Integer.decode( opts.substring(index+1).toString() );
			if (dbg) StdStreams.vrb.println("[TARGET] Hostname for Telnet set to : " + hostname.toString());
			if (dbg) StdStreams.vrb.println("[TARGET] Port for Telnet set to : " + String.valueOf(port));
		}
	}

	@Override
	public void closeConnection() {
		try {
			if (socket != null) {
				out.write(("shutdown\r\n").getBytes());
				socket.close();
			}
		} catch (IOException e) {
			// do nothing
		}
		if (dbg) StdStreams.vrb.println("[TARGET] Connection closed");	
	}

	@Override
	public boolean isConnected() {
		if (dbg) StdStreams.vrb.println("[TARGET] check for target connection");	
		try {
			if (socket == null || socket.getInputStream() == null) return false;
			if (dbg) StdStreams.vrb.println("[TARGET] check returns " + (socket == null) + " " + socket.isConnected() + " " + socket.isClosed() + " " + socket.isOutputShutdown());	
			int ch = socket.getInputStream().read();
			if (dbg) StdStreams.vrb.println("[TARGET] read returns " + ch);
			if (dbg) StdStreams.vrb.println("[TARGET] available " + socket.getInputStream().available());
			// if no target is connected, the server outputs lots of error messages
			// the limit must be chosen to be bigger that what the server delivers after writing to memory or registers
			// the answers to reading from memory or registers is consumed by the reading itself, therefore this is never critical 
			if (socket.getInputStream().available() >= 500) {
				if (dbg) {
					while (in.available() > 0) StdStreams.vrb.print((char)in.read());
				}
				out.write(("shutdown\r\n").getBytes());
				if (dbg) StdStreams.vrb.println("[TARGET] shutdown server");
				long time = System.currentTimeMillis();
				while (System.currentTimeMillis() - time < 1000);	// wait for shutdown
				return false;
			}
			if (ch == -1) {
				return false;
			}
		} catch (IOException e) {
			return false;
		}
		return (socket.isConnected() && !socket.isClosed());
	}
	
	@Override
	public int getTargetState() throws TargetConnectionException {
		int timeout = 5;		// in sleep cycles of 100ms
		try {
			in.skip(in.available());
			out.write(("wait_halt 10 \r\n").getBytes());		// check if system is halted
			int count = 0;
			while (true) {
				int n = in.available();
				if (n <= 0) {
					Thread.sleep(100);
					timeout--;
					if (timeout == 0) throw new TargetConnectionException("getTargetState() : unexpected answer");
				} else { 
					int c = in.read();
					count++;
					if (c < 0) throw new TargetConnectionException("target not answering");	
					if (count == 18) {
						if ((char)c == '>')	return stateDebug; else return stateRunning;
					}
				}
			}
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void startTarget(int address) throws TargetConnectionException {
		try {
			if (address != -1) {
				if (dbg) StdStreams.vrb.println("[TARGET] arm: Starting from 0x" + Integer.toHexString(address));
				out.write((("resume " + address + "\r\n").getBytes()));
			} else {
				if (dbg) StdStreams.vrb.println("[TARGET] Resume target");
				out.write(("resume\r\n".getBytes()));
			}
			waitForNL(1);
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void stopTarget() throws TargetConnectionException {
		try {
			out.write(("halt\r\n".getBytes()));
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		if (dbg) StdStreams.vrb.println("[TARGET] stopped");
	}

	@Override
	public void resetTarget() throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] Reseting");
		try {
			out.write(("reset\r\n".getBytes()));
			Thread.sleep(1000);
			out.write(("halt\r\n".getBytes()));
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void setRegisterValue(String regName, long value) throws TargetConnectionException {
		Register reg = Configuration.getRegisterByName(regName);
		if (reg != null) setRegisterValue(reg, value);
	}

	@Override
	public void setRegisterValue(Register reg, long value) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET]   Setting register " + reg.name + " to 0x" + Long.toHexString((int)value));
			switch (reg.regType) { 
			case Parser.sFPR:
				setFprValue(reg.address, value);
				break;
			case Parser.sFPSCR:
				setFpscrValue(value);
				break;
			case Parser.sCPR:
				setCprValue(reg.address, value);
			case Parser.sIOR:	// is used solely by launcher not by target operation view
				writeWord(reg.address, (int)value);
				break;
			default:	// sGPR
				out.write(("reg " + reg.address + " 0x" + Integer.toHexString((int)value) + "\r\n").getBytes());
			}
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public long getRegisterValue(String regName) throws TargetConnectionException {
		Register reg = Configuration.getRegisterByName(regName);
		if (reg != null) return getRegisterValue(reg);
		return defaultValue;
	}

	@Override
	public long getRegisterValue(Register reg) throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("\r\n[TARGET] read from register " + reg.name);
		switch (reg.regType) {
		case Parser.sGPR:
			return getGprValue(reg.address);
		case Parser.sFPR:
			return getFprValue(reg.address);
		case Parser.sCPR:
			return getCprValue(reg.address);
		case Parser.sFPSCR:
			return getFpscrValue();
		case Parser.sIOR:	// is used solely by launcher not by target operation view
			return readWord(reg.address);
		default:
			return defaultValue;
		}
	}

	@Override
	public long[] getRegisterBlock(String block) throws TargetConnectionException {
		return null;
	}
	
	@Override
	public byte readByte(int address) throws TargetConnectionException {
		byte[] cmd = ("mdb 0x" + Integer.toHexString(address) + "\r\n").getBytes();
		return (byte)getMemLocation(cmd, 2);
	}

	@Override
	public short readHalfWord(int address) throws TargetConnectionException {
		byte[] cmd = ("mdh 0x" + Integer.toHexString(address) + "\r\n").getBytes();
		return (short)getMemLocation(cmd, 4);
	}

	@Override
	public int readWord(int address) throws TargetConnectionException {
		byte[] cmd = ("mdw 0x" + Integer.toHexString(address) + "\r\n").getBytes();
		return getMemLocation(cmd, 8);
	}

	@Override
	public void writeByte(int address, byte data) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET] writing byte 0x" + Integer.toHexString(data) + " to address 0x" + Integer.toHexString(address) + " (" + address + ")");
			out.write(("mwb 0x" + Integer.toHexString(address) + " 0x" + Integer.toHexString(data) + "\r\n").getBytes());
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void writeHalfWord(int address, short data) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET] writing half word 0x" + Integer.toHexString(data) + " to address 0x" + Integer.toHexString(address) + " (" + address + ")");
			out.write(("mwh 0x" + Integer.toHexString(address) + " 0x" + Integer.toHexString(data) + "\r\n").getBytes());
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void writeWord(int address, int data) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET] writing word 0x" + Integer.toHexString(data) + " to address 0x" + Integer.toHexString(address) + " (" + address + ")");
			out.write(("mww 0x" + Integer.toHexString(address) + " 0x" + Integer.toHexString(data) + "\r\n").getBytes());
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void writeTMS(TargetMemorySegment tms) throws TargetConnectionException {
		StdStreams.err.println("[TARGET] writeTMS(TargetMemorySegment tms) is not supportet with OpenOCD");
	}

	@Override
	public void downloadImageFile(String filename) throws TargetConnectionException {
		try {
			socket.setSoTimeout(1000);
			while (in.available() > 0) in.read();	// empty buffer
			out.write((("halt\r\n").getBytes()));

			StringBuffer buf = new StringBuffer();
			ArrayList<Map.Entry<String,Integer>> files = Configuration.getImgFile();
			if (files.isEmpty()) StdStreams.err.println("no image files available");
			for (Map.Entry<String,Integer> file : files) {
				socket.setSoTimeout(8000);
				String name = file.getKey();
				name = name.replace('\\', '/');
				StdStreams.log.println("Downloading " + name);
				out.write((("load_image \"" + name + "\" " + file.getValue() + " \r\n").getBytes()));
				if (dbg) StdStreams.vrb.println("[TARGET] loading: " + name + " to addr 0x" + Integer.toHexString(file.getValue()));
				buf = new StringBuffer();
				while (true) {
					int n = in.available();
					if (n <= 0) Thread.sleep(100);
					int c = in.read();
					if (c < 0) throw new TargetConnectionException("target not answering");
					buf.append((char)c);
					if (buf.indexOf("downloaded") > 0) {
						waitForNL(1);
						break;
					}
				}
			}

			/* init PL */
			HString fname = Configuration.getPlFileName();
			if (fname != null) {
				String name = new File(fname.toString()).getCanonicalPath().replace('\\', '/');
				while (in.available() > 0) in.read();	// empty buffer
				StdStreams.log.print("Downloading bitstream " + name + " ");
				out.write(("pld load 0 \"" + name + "\"\r\n").getBytes());
				socket.setSoTimeout(10000);
				while (true) {
					int n = in.available();
					if (n <= 0) {
						Thread.sleep(100);
						StdStreams.log.print(".");
					}
					int c = in.read();
					if (c < 0) throw new TargetConnectionException("target not answering");

					buf.append((char)c);
					if (buf.indexOf("loaded file") > 0) {
						waitForNL(1);
						StdStreams.log.println(" download complete");
						break;
					}
					if (buf.indexOf("No such file") > 0) {
						waitForNL(1);
						throw new TargetConnectionException("no such file");
					}
				}
			}
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}		
	}

	@Override
	public void setBreakPoint(int address) throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] setting breakpoint @ 0x" + Integer.toHexString(address) + ")\r\n");
		try {
			out.write(("bp " + address + " 1 hw\r\n").getBytes());
		} catch (IOException e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void removeBreakPoint(int address) throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] remove breakpoint @ 0x" + Integer.toHexString(address) + ")\r\n");
		try {
			out.write(("rbp " + address + "\r\n").getBytes());
		} catch (IOException e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void confirmBreakPoint(int address) throws TargetConnectionException {
		// TODO Auto-generated method stub
		
	}

	private synchronized int getGprValue(int gpr) throws TargetConnectionException {
		byte[] value = new byte[9];
		int c, j = 0, val = 0, start = 999, bracketPosition = 999;
		try {
			in.skip(in.available());
			out.write(("reg " + gpr + "\r\n").getBytes());			
			while((c = in.read()) != -1) {
					if (c < 0) throw new TargetConnectionException("target not answering");
					if ((char)c == '(') bracketPosition = j;
					if (j == bracketPosition + 5) {
						if ((char)c == ':')	start = bracketPosition + 9;
						else bracketPosition = 999;
					}				
					if (j >= start && j<= start+7) value[j - start ] = (byte) c;
					if (j == start + 7 ) {val = parseHex(value, 8); break;}
					j++;
			}
			if (dbg) StdStreams.vrb.println("[TARGET] GPR" + gpr + " val: 0x" + Integer.toHexString(val));
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		return val;
	}
	
	private long getFprValue(int fpr) throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] getFprValue (" + fpr + ")");
		final int memAddrStart = 0x64;
		int instruction = 0xEC510B10;	// VMOV	R0, R1, fpr
		instruction = instruction | ((fpr & 0x10) << 1) | (fpr & 0xf);		
		// backup registers and memory
		int pcStored = getGprValue(15);
		int r0Stored = getGprValue(0);
		int r1Stored = getGprValue(1);
		int memValue = readWord(memAddrStart);
		writeWord(memAddrStart, instruction);
		setBreakPoint(memAddrStart + 4);
		startTarget(memAddrStart);	// set PC to 0x64 and continue
		int r0Float = getGprValue(0);
		int r1Float = getGprValue(1);
		long fprValue = ((long)r1Float << 32) | ((long)r0Float & 0xffffffffL);
		if (dbg) StdStreams.vrb.println("[TARGET] read FPU registers fprValue: 0x" + Long.toHexString(fprValue));
		removeBreakPoint(memAddrStart + 4);
		// restore registers and memory
		writeWord(memAddrStart, memValue);
		setRegisterValue("PC", pcStored);
		setRegisterValue("R0", r0Stored);
		setRegisterValue("R1", r1Stored);
		return fprValue;
	}

	private long getFpscrValue() throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] getFpscrValue");
		final int memAddrStart = 0x64;
		int instruction = 0xEEF10A10;	// VMRS R0, FPSCR
		// backup registers and memory
		int pcStored = getGprValue(15);
		int r0Stored = getGprValue(0);
		int memValue = readWord(memAddrStart);
		writeWord(memAddrStart, instruction);
		setBreakPoint(memAddrStart + 4);
		startTarget(memAddrStart);	// set PC to 0x64 and continue
		int fpscr = getGprValue(0);
		if (dbg) StdStreams.vrb.println("[TARGET] read FPSCR value: 0x" + Long.toHexString(fpscr));
		removeBreakPoint(memAddrStart + 4);
		// restore registers and memory
		writeWord(memAddrStart, memValue);
		setRegisterValue("PC", pcStored);
		setRegisterValue("R0", r0Stored);
		return fpscr;
	}

	private long getCprValue(int addr) throws TargetConnectionException {
		int coproc = (addr >> 16) & 0xf;
		int CRn = (addr >> 12) & 0xf;
		int opc1 = (addr >> 8) & 0xf;
		int CRm = (addr >> 4) & 0xf;
		int opc2 = addr & 0xf;
		if (dbg) StdStreams.vrb.println("[TARGET] getCprValue (" + coproc + ", " + CRn + ", " + opc1 + ", " + CRm + ", " + opc2 + ")");
		final int memAddrStart = 0x64;
		int instruction = 0xEE100010;	// MRC	coproc, opc1, R0, CRn, CRm, opc2
		instruction = instruction | (coproc << 8) | (CRn << 16) | CRm | (opc1 << 21) | (opc2 << 5);		
		// backup registers and memory
		int pcStored = getGprValue(15);
		int r0Stored = getGprValue(0);
		int memValue = readWord(memAddrStart);
		writeWord(memAddrStart, instruction);
		setBreakPoint(memAddrStart + 4);
		startTarget(memAddrStart);	// set PC to 0x64 and continue
		int cprValue = getGprValue(0);
		if (dbg) StdStreams.vrb.println("[TARGET] read CPR registers value: 0x" + Long.toHexString(cprValue));
		removeBreakPoint(memAddrStart + 4);
		// restore registers and memory
		writeWord(memAddrStart, memValue);
		setRegisterValue("PC", pcStored);
		setRegisterValue("R0", r0Stored);
		return cprValue;
	}

	private void setFprValue(int addr, long value) throws TargetConnectionException {
		final int memAddrStart = 0x64;
		int instruction = 0xEC410B10;	// VMOV	Di, R0, R1
		instruction = instruction | ((addr & 0x10) << 1) | (addr & 0xf);
		if (dbg) StdStreams.vrb.println("[TARGET] setFprValue instruction: 0x" + Integer.toHexString(instruction));
		// backup registers and memory
		int pcStored = getGprValue(15);
		int r0Stored = getGprValue(0);
		int r1Stored = getGprValue(1);
		int memValue = readWord(memAddrStart);
		setRegisterValue("R0", value & 0xffffffff);
		setRegisterValue("R1", (value >> 32) & 0xffffffff);
		writeWord(memAddrStart, instruction);
		setBreakPoint(memAddrStart + 4);
		startTarget(memAddrStart);	// set PC to 0x64 and continue
		removeBreakPoint(memAddrStart + 4);
		// restore registers and memory
		writeWord(memAddrStart, memValue);
		setRegisterValue("PC", pcStored);
		setRegisterValue("R0", r0Stored);
		setRegisterValue("R1", r1Stored);
	}

	private void setFpscrValue(long value) throws TargetConnectionException {
		final int memAddrStart = 0x64;
		int instruction = 0xEEE10A10;	// VMSR	FPSCR, R0
		if (dbg) StdStreams.vrb.println("[TARGET] setFpscrValue instruction: 0x" + Integer.toHexString(instruction));
		// backup registers and memory
		int pcStored = getGprValue(15);
		int r0Stored = getGprValue(0);
		int memValue = readWord(memAddrStart);
		setRegisterValue("R0", value);
		writeWord(memAddrStart, instruction);
		setBreakPoint(memAddrStart + 4);
		startTarget(memAddrStart);	// set PC to 0x64 and continue
		removeBreakPoint(memAddrStart + 4);
		// restore registers and memory
		writeWord(memAddrStart, memValue);
		setRegisterValue("PC", pcStored);
		setRegisterValue("R0", r0Stored);
	}
	
	private void setCprValue(int addr, long value) throws TargetConnectionException {
		int coproc = (addr >> 16) & 0xf;
		int CRn = (addr >> 12) & 0xf;
		int opc1 = (addr >> 8) & 0xf;
		int CRm = (addr >> 4) & 0xf;
		int opc2 = addr & 0xf;
		if (dbg) StdStreams.vrb.println("[TARGET] setCprValue (" + coproc + ", " + CRn + ", " + opc1 + ", " + CRm + ", " + opc2 + ")");
		final int memAddrStart = 0x64;
		int instruction = 0xEE001010;	// MCR	coproc, opc1, R1, CRn, CRm, opc2
		instruction = instruction | (coproc << 8) | (CRn << 16) | CRm | (opc1 << 21) | (opc2 << 5);		
		// backup registers and memory
		int pcStored = getGprValue(15);
		int r0Stored = getGprValue(1);
		int memValue = readWord(memAddrStart);
		writeWord(memAddrStart, instruction);
		setRegisterValue("R1", value);
		setBreakPoint(memAddrStart + 4);
		startTarget(memAddrStart);	// set PC to 0x64 and continue
		removeBreakPoint(memAddrStart + 4);
		// restore registers and memory
		writeWord(memAddrStart, memValue);
		setRegisterValue("PC", pcStored);
		setRegisterValue("R1", r0Stored);
	}

	private int getMemLocation(byte[] cmd, int nofBytes) throws TargetConnectionException {
		byte[] value = new byte[9];
		int c, j = 0, val = 0, start = 999, xPosition = 999;
		if (dbg) StdStreams.vrb.println("[TARGET] reading from memory");
		try {
			in.skip(in.available());
			out.write(cmd);
			while ((c = in.read()) != -1) {
				if (c < 0) throw new TargetConnectionException("[TARGET] target not answering");
				if ((char)c == 'x') xPosition = j;
				if (j == xPosition + 9) {
					if ((char)c == ':') start = j + 2;
					else xPosition = 999;
				}				
				if (j >= start) value[j - start ] = (byte) c;
				if (j == start + nofBytes - 1) {val = parseHex(value, nofBytes); break;}
				j++;
			}
			if (dbg) StdStreams.vrb.println("[TARGET] " + val);		
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		return val;
	}

	private void waitForNL(int nofNL) throws Exception {
		while (true) {
			int n = in.available();
			if (n <= 0) Thread.sleep(100);
			int c = in.read();
			if ((char)c == '\n') nofNL--;
			if (c < 0) throw new TargetConnectionException("target not answering");
			if (nofNL == 0) break;
		}
	}
	
	private int parseHex(byte[] hex, int len)  {
		int value = 0;
		for (int i = 0; i < len; i++) {
			value <<= 4;
			if (hex[i] >= '0' && hex[i] <= '9') value += (byte)(hex[i] - (byte)'0');
			else {
				byte c = (byte)Character.toLowerCase(hex[i]);
				value += (byte)(c - (byte)'a' + (byte)10);
			}
		}
		return value;
	}
}
