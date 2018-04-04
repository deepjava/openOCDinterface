package ch.ntb.inf.openOCDInterface;

import java.net.Socket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import ch.ntb.inf.deep.config.Configuration;
import ch.ntb.inf.deep.config.Parser;
import ch.ntb.inf.deep.config.Register;
import ch.ntb.inf.deep.host.StdStreams;
import ch.ntb.inf.deep.linker.TargetMemorySegment;
import ch.ntb.inf.deep.strings.HString;
import ch.ntb.inf.deep.target.TargetConnection;
import ch.ntb.inf.deep.target.TargetConnectionException;

public class OpenOCD extends TargetConnection {

	private static boolean dbg = true;

//	final static int SOH = 1;
//	final static int ETX = 3;
//	final static int WILL = 251;
//	final static int IAC = 255;

	private static TargetConnection tc;
	OpenOCDServer oos;
	String hostname = "localhost";
	int port = 4444;
	Socket socket;
	OutputStream out;
	InputStream in;

	private OpenOCD() {
		if(dbg) StdStreams.vrb.println("[TARGET] OpenOCD konstruktor");
//		oos = OpenOCDServer.getInstance();
	}
	
	
	//OK
	public static TargetConnection getInstance() {
		if (tc != null && !tc.isConnected()) tc = null;
		if (tc == null) {
			if(dbg) StdStreams.vrb.println("[TARGET] AbatronTelnet: Creating new Abatron Telnet");
			tc = new OpenOCD();
		}
		return tc;
	}
		
	// OK
	@Override
	public void openConnection() throws TargetConnectionException {
		try {
			socket = new Socket(hostname, port);
			out = socket.getOutputStream();
			in = socket.getInputStream();
			if (dbg) StdStreams.vrb.println("[TARGET] Connected ");
		} catch (Exception e) {
			if (dbg) StdStreams.vrb.println("[TARGET] Connection failed on " + hostname);
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	// OK
	@Override
	public void setOptions(HString opts) {
		int index = opts.indexOf('_');		// ':' not possible due to parser problem
		if ( index  == -1 ) {
//			throw new TargetConnectionException("target not answering");
			StdStreams.err.println("[TARGET] programmeropts need to be in format 'hostname_port'. I.e. 'localhost_4444");
		}
		else {
			hostname = opts.substring(0, index).toString() ;
			port = Integer.decode( opts.substring(index+1).toString() );
			if (dbg) StdStreams.vrb.println("[TARGET] Hostname for Telnet set to : " + hostname.toString());
			if (dbg) StdStreams.vrb.println("[TARGET] Port for Telnet set to : " + String.valueOf(port));
		}
	}

	// OK
	@Override
	public void closeConnection() {
		try {
			if (socket != null) socket.close();
			socket = null;
			out = null;
			in = null;
		} catch (IOException e) {
			// do nothing
		}
		if (dbg) StdStreams.vrb.println("[TARGET] Connection closed");	
	}

	// OK
	@Override
	public boolean isConnected() {
		if (socket == null) return false;
		return (socket.isConnected() && !socket.isClosed());
	}
	
	// OK
	@Override
	public int getTargetState() throws TargetConnectionException {
		int j = 0, start=9999;
		int timeout = 5;		// in sleepcycles of 100 msec

		try {
			in.skip(in.available());
			out.write(("mdb 0x0 \r\n").getBytes());		// try to read memory to check if system is halted
			while (true) {
				int n = in.available();
				if (n <= 0) {
					Thread.sleep(100);
					timeout--;
					if (timeout == 0)	throw new TargetConnectionException("getTargetState() : unexpected answer");
				}
				else { 
					int c = in.read();
					if (c < 0) 
						throw new TargetConnectionException("target not answering");
					
					if ( (char)c == ':' ) { start = j + 2;  }
					if (j == start) {
						if (dbg) StdStreams.vrb.println("[TARGET] getTargetState() char: " + (char)c + " \r\n");
						if ( (char)c == '0' )	return stateDebug;		// 0x00000000: 00
						else 					return stateRunning;	// Error: cortex_a_mmu: target not halted
					}
					
					j++;
				}
			}
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}


	// "resume at address" not tested
	@Override
	public void startTarget(int address) throws TargetConnectionException {
		try {
			if (address != -1) {
					if (dbg) StdStreams.vrb.println("[TARGET] arm: Starting from 0x" + Integer.toHexString(address+0x0000000));
//					out.write((("resume " + (address+0x0000000) + "\r\n").getBytes()));
					out.write((("resume " + address + "\r\n").getBytes()));
			} else {
				if (dbg) StdStreams.vrb.println("[TARGET] Resume target");
				out.write(("resume\r\n".getBytes()));
			}
//			waitForPrompt();
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	// OK
	@Override
	public void stopTarget() throws TargetConnectionException {
		try {
			out.write(("halt\r\n".getBytes()));
//			waitForPrompt();
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		if (dbg) StdStreams.vrb.println("[TARGET] stopped");
	}

	// OK
	@Override
	public void resetTarget() throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] Reseting");
		try {
			out.write(("reset halt\r\n".getBytes()));
//			waitForPrompt();
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
			default:
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
		if (dbg) StdStreams.vrb.println("[TARGET] read from register " + reg.name);
		switch(reg.regType) {
		case Parser.sGPR:
			return getGprValue(reg.address);
		case Parser.sFPR:
			return getFprValue(reg.address);
//		case Parser.sSPR:
//			return getSprValue(reg.address);
//		case Parser.sIOR:
//			return getIorValue(reg.address);
//		case Parser.sMSR:
//			return getMsrValue();
//		case Parser.sCR:
//			return getCrValue();
		case Parser.sFPSCR:
			return getFpscrValue();
		default:
			return defaultValue;
		}
	}


	// OK
	@Override
	public byte readByte(int address) throws TargetConnectionException {
		byte[] value = new byte[9];
		int j = 0, val = 0, start=999;
		int xPosition = 999;

		try {
			in.skip(in.available());
			out.write(("mdb 0x" + Integer.toHexString(address) +	" \r\n").getBytes());
			
			int c;
			while((c = in.read())!=-1) {
					if (c < 0) 
						throw new TargetConnectionException("target not answering");
					
					if ( (char)c == 'x' ) { xPosition = j;  }		// 0x00000100: 04
					if (j == xPosition+9) {
						if ( (char)c == ':' )	start = xPosition + 11;
						else					xPosition = 999;
					}				
					if (j >= start) {
						value[j - start ] = (byte) c;
//						if (dbg) StdStreams.vrb.println("[TARGET] start: " + (j-start) + " : "+ (char)c + " \r\n");
					}
					
					if (j == start+1 ) {val = parseHex(value, 2); break;}
					
					j++;
			}
			if (dbg) StdStreams.vrb.println();		
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		return (byte)val;
	}

	// OK
	@Override
	public short readHalfWord(int address) throws TargetConnectionException {
		byte[] value = new byte[9];
		int j = 0, val = 0, start=999;
		int xPosition = 999;

		try {
			in.skip(in.available());
			out.write(("mdh 0x" + Integer.toHexString(address) +	" \r\n").getBytes());
			
			int c;
			while((c = in.read())!=-1) {
					if (c < 0) 
						throw new TargetConnectionException("target not answering");
					
					if ( (char)c == 'x' ) { xPosition = j;  }		// 0x00000100: 0004
					if (j == xPosition+9) {
						if ( (char)c == ':' )	start = xPosition + 11;
						else					xPosition = 999;
					}				
					if (j >= start) {
						value[j - start ] = (byte) c;
//						if (dbg) StdStreams.vrb.println("[TARGET] start: " + (j-start) + " : "+ (char)c + " \r\n");
					}
					
					if (j == start+3 ) {val = parseHex(value, 4); break;}
					
					j++;
			}
			if (dbg) StdStreams.vrb.println();		
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		return (short)val;
	}

	// OK
	@Override
	public int readWord(int address) throws TargetConnectionException {
		byte[] value = new byte[9];
		int j = 0, val = 0, start=999;
		int xPosition = 999;

		try {
			in.skip(in.available());
			out.write(("mdw 0x" + Integer.toHexString(address) +	" \r\n").getBytes());
			
			int c;
			while((c = in.read())!=-1) {
					if (c < 0) 
						throw new TargetConnectionException("target not answering");
					
					if ( (char)c == 'x' ) { xPosition = j;  }		// 0x00000100: e4101004
					if (j == xPosition+9) {
						if ( (char)c == ':' )	start = xPosition + 11;
						else					xPosition = 999;
					}				
					if (j >= start) {
						value[j - start ] = (byte) c;
//						if (dbg) StdStreams.vrb.println("[TARGET] start: " + (j-start) + " : "+ (char)c + " \r\n");
					}
					
					if (j == start+7 ) {val = parseHex(value, 8); break;}
					
					j++;
			}
			if (dbg) StdStreams.vrb.println();		
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		return val;
	}

	// not tested
	@Override
	public void writeByte(int address, byte data) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET] writing byte 0x" + Integer.toHexString(data) + " to address 0x" + Integer.toHexString(address) + " (" + address + ")");
			out.write(("mwb 0x" + Integer.toHexString(address) + " 0x" + Integer.toHexString(data) + "\r\n").getBytes());
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	// not tested
	@Override
	public void writeHalfWord(int address, short data) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET] writing half word 0x" + Integer.toHexString(data) + " to address 0x" + Integer.toHexString(address) + " (" + address + ")");
			out.write(("mwh 0x" + Integer.toHexString(address) + " 0x" + Integer.toHexString(data) + "\r\n").getBytes());
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	// OK
	@Override
	public void writeWord(int address, int data) throws TargetConnectionException {
		try {
			if (dbg) StdStreams.vrb.println("[TARGET] writing word 0x" + Integer.toHexString(data) + " to address 0x" + Integer.toHexString(address) + " (" + address + ")");
			out.write(("mww 0x" + Integer.toHexString(address) + " 0x" + Integer.toHexString(data) + "\r\n").getBytes());
//			waitForPrompt();
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
	}

	@Override
	public void writeTMS(TargetMemorySegment tms) throws TargetConnectionException {
		StdStreams.err.println("[TARGET] writeTMS(TargetMemorySegment tms) is not supportet with OpenOCD");
		// not supported here
	}

	// OK
	@Override
	public void downloadImageFile(String filename) throws TargetConnectionException {
		try {
//			int pos = filename.indexOf("ftp");
			String name = filename;
			name = name.replace('\\', '/');
			out.write((("halt\r\n").getBytes()));
//			out.write((("load_image " + name + " ; resume 0x100\r\n").getBytes()));
			out.write((("load_image " + name + " \r\n").getBytes()));
			
//			if (Configuration.getBoard().cpu.arch.name.equals(HString.getHString("arm32"))) {
//				name = name.replaceAll(".bin", ".InternalRam.bin");
//				out.write((("mmu disable; load 0x0000000 " + name + " bin\r\n").getBytes()));				
//			} else { 
//				name = name.replaceAll(".bin", ".ExternalRam.bin");
//				out.write((("load 0x0 " + name + " bin\r\n").getBytes()));
//			}
			if (dbg) StdStreams.vrb.println("[TARGET] loading: " + name);
			StdStreams.log.println("[TARGET] .....");
//			waitForPrompt();
		} catch (Exception e) {
			new TargetConnectionException(e.getMessage(), e);
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

	/* private methods */
	
	private synchronized int getGprValue(int gpr) throws TargetConnectionException {
		byte[] value = new byte[9];
		int j = 0, val = 0, c;
		int start=999;
		int xPosition = 999;

		try {
			in.skip(in.available());
			out.write(("reg " + gpr + "\r\n").getBytes());			

			while((c = in.read())!=-1) {
					if (c < 0) 
						throw new TargetConnectionException("target not answering");
					
					if ( (char)c == '(' ) { xPosition = j;  }		// 0x00000100: 04
					if (j == xPosition+5) {
						if ( (char)c == ':' )	start = xPosition + 9;
						else					xPosition = 999;
					}				
					if (j >= start && j<= start+7) {
						value[j - start ] = (byte) c;
//						if (dbg) StdStreams.vrb.println("[TARGET] start: " + (j-start) + " : "+ (char)c + " \r\n");
					}
					
					if (j == start+7 ) {val = parseHex(value, 8); break; }
					
					j++;
			}

			if (dbg) StdStreams.vrb.println("[TARGET] GPR" + gpr + " val: 0x" + Integer.toHexString(val));
//			if (dbg) StdStreams.vrb.println();
		} catch (Exception e) {
			throw new TargetConnectionException(e.getMessage(), e);
		}
		return val;
	}
	
	private synchronized long getFprValue(int fpr) throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] getFprValue (" + fpr + ")\r\n");
		final int memAddrStart = 0x64;
		final int nofInstr = 1;
//		final int vmovR0R1D0MachineCode = 0xEC510B10;
		
		int instruction = 0xEC510B10;	// VMOV	R0, R1, D0
		instruction = instruction | ((fpr & 0x10) << 1) | (fpr & 0xf);
		if (dbg) StdStreams.vrb.println("[TARGET] getFprValue instruction: 0x" + Integer.toHexString(instruction));
		
		// store r15 (PC)
		int pcStored = getGprValue(15);
		// store r0, r1
		int r0Stored = getGprValue(0);
		int r1Stored = getGprValue(1);
		// store 1x4 bytes @ 0x64
		int memValue = readWord(memAddrStart);
		
//		if (dbg) StdStreams.vrb.println("[TARGET] store pc: 0x" + Integer.toHexString(pcStored));
//		if (dbg) StdStreams.vrb.print(", r0: 0x" + Integer.toHexString(r0Stored));
//		if (dbg) StdStreams.vrb.print(", r1: 0x" + Integer.toHexString(r1Stored) + "\r\n");
//		if (dbg) StdStreams.vrb.print("          mem: 0x" + Integer.toHexString(memValue));
//		if (dbg) StdStreams.vrb.print(", @ 0x" + Integer.toHexString(memAddrStart) + "\r\n");
		
		
		// write 1x4 bytes @ 0x64 ("vmov r0, r1, d0")
		writeWord(memAddrStart, instruction);
		// set breakpoint to 0x68 (0x64 + nofInstr*4)
		setBreakPoint(memAddrStart + nofInstr*4);
		// set PC to 0x64
		// continue CPU
		startTarget(memAddrStart);
		
		// read r0, r1
		int r0Float = getGprValue(0);
		int r1Float = getGprValue(1);
		// parse r0, r1
		long fprValue = ((long)r1Float << 32) | ((long)r0Float & 0xffffffffL);

//		if (dbg) StdStreams.vrb.println("[TARGET] read FPU registers r0: 0x" + Integer.toHexString(r0Float));
//		if (dbg) StdStreams.vrb.print(", r1: 0x" + Integer.toHexString(r1Float) + "\r\n");
		if (dbg) StdStreams.vrb.println("[TARGET] read FPU registers fprValue: 0x" + Long.toHexString(fprValue));
		
		
		// remove breakpoint
		removeBreakPoint(memAddrStart + nofInstr*4);
		// restore 1x4 bytes
		writeWord(memAddrStart, memValue);
		// restore r0, r1, pc
		setRegisterValue("R0", r0Stored);
		setRegisterValue("R1", r1Stored);
		setRegisterValue("PC", pcStored);
		

		return fprValue;
	}
	
	//not tested
	private long getFpscrValue() throws TargetConnectionException {
		if (dbg) StdStreams.vrb.println("[TARGET] getFpscrValue \r\n");
		final int memAddrStart = 0x64;
		final int nofInstr = 1;
		
		int instruction = 0xEEF1_0A10;	// VMRS R0, FPSCR
		
		// store r15 (PC)
		int pcStored = getGprValue(15);
		// store r0
		int r0Stored = getGprValue(0);
		// store 1x4 bytes @ 0x64
		int memValue = readWord(memAddrStart);
		
//		if (dbg) StdStreams.vrb.println("[TARGET] store pc: 0x" + Integer.toHexString(pcStored));
//		if (dbg) StdStreams.vrb.print(", r0: 0x" + Integer.toHexString(r0Stored));
//		if (dbg) StdStreams.vrb.print(", r1: 0x" + Integer.toHexString(r1Stored) + "\r\n");
//		if (dbg) StdStreams.vrb.print("          mem: 0x" + Integer.toHexString(memValue));
//		if (dbg) StdStreams.vrb.print(", @ 0x" + Integer.toHexString(memAddrStart) + "\r\n");
		
		
		// write 1x4 bytes @ 0x64
		writeWord(memAddrStart, instruction);
		// set breakpoint to 0x68 (0x64 + nofInstr*4)
		setBreakPoint(memAddrStart + nofInstr*4);
		// set PC to 0x64
		// continue CPU
		startTarget(memAddrStart);
		
		// read r0,
		int fpscr = getGprValue(0);
		if (dbg) StdStreams.vrb.println("[TARGET] read FPSCR value: 0x" + Long.toHexString(fpscr));
		
		
		// remove breakpoint
		removeBreakPoint(memAddrStart + nofInstr*4);
		// restore 1x4 bytes
		writeWord(memAddrStart, memValue);
		// restore r0, r1, pc
		setRegisterValue("R0", r0Stored);
		setRegisterValue("PC", pcStored);
		

		return fpscr;
	}

	private void setFprValue(int addr, long value) throws TargetConnectionException {
		final int memAddrStart = 0x64;
		final int nofInstr = 1;
		
		int instruction = 0xEC41_0B10;	// VMOV	D0, R0, R1
		instruction = instruction | ((addr & 0x10) << 1) | (addr & 0xf);
		if (dbg) StdStreams.vrb.println("[TARGET] setFprValue instruction: 0x" + Integer.toHexString(instruction));
		
		// store r15 (PC)
		int pcStored = getGprValue(15);
		// store r0, r1
		int r0Stored = getGprValue(0);
		int r1Stored = getGprValue(1);
		// store 1x4 bytes @ 0x64
		int memValue = readWord(memAddrStart);
		
//		if (dbg) StdStreams.vrb.println("[TARGET] store pc: 0x" + Integer.toHexString(pcStored));
//		if (dbg) StdStreams.vrb.print(", r0: 0x" + Integer.toHexString(r0Stored));
//		if (dbg) StdStreams.vrb.print(", r1: 0x" + Integer.toHexString(r1Stored) + "\r\n");
//		if (dbg) StdStreams.vrb.print("          mem: 0x" + Integer.toHexString(memValue));
//		if (dbg) StdStreams.vrb.print(", @ 0x" + Integer.toHexString(memAddrStart) + "\r\n");
		
		// set r0, r1
		long r0 = value & 0x0000_ffff;
		long r1 = (value & 0xffff_0000) >> 32;
		setRegisterValue("R0", r0);
		setRegisterValue("R1", r1);
		
		// write 1x4 bytes @ 0x64
		writeWord(memAddrStart, instruction);
		// set breakpoint to 0x68 (0x64 + nofInstr*4)
		setBreakPoint(memAddrStart + nofInstr*4);
		// set PC to 0x64
		// continue CPU
		startTarget(memAddrStart);
		
		// remove breakpoint
		removeBreakPoint(memAddrStart + nofInstr*4);
		// restore 1x4 bytes
		writeWord(memAddrStart, memValue);
		// restore r0, r1, pc
		setRegisterValue("R0", r0Stored);
		setRegisterValue("R1", r1Stored);
		setRegisterValue("PC", pcStored);
	}

	private void setFpscrValue(long value) throws TargetConnectionException {
		final int memAddrStart = 0x64;
		final int nofInstr = 1;
		
		int instruction = 0xEEE1_0A10;	// VMSR	FPSCR, R0
		if (dbg) StdStreams.vrb.println("[TARGET] setFpscrValue instruction: 0x" + Integer.toHexString(instruction));
		
		// store r15 (PC)
		int pcStored = getGprValue(15);
		// store r0
		int r0Stored = getGprValue(0);
		// store 1x4 bytes @ 0x64
		int memValue = readWord(memAddrStart);
		
//		if (dbg) StdStreams.vrb.println("[TARGET] store pc: 0x" + Integer.toHexString(pcStored));
//		if (dbg) StdStreams.vrb.print(", r0: 0x" + Integer.toHexString(r0Stored));
//		if (dbg) StdStreams.vrb.print(", r1: 0x" + Integer.toHexString(r1Stored) + "\r\n");
//		if (dbg) StdStreams.vrb.print("          mem: 0x" + Integer.toHexString(memValue));
//		if (dbg) StdStreams.vrb.print(", @ 0x" + Integer.toHexString(memAddrStart) + "\r\n");
		
		// set r0
		setRegisterValue("R0", value);
		
		// write 1x4 bytes @ 0x64
		writeWord(memAddrStart, instruction);
		// set breakpoint to 0x68 (0x64 + nofInstr*4)
		setBreakPoint(memAddrStart + nofInstr*4);
		// set PC to 0x64
		// continue CPU
		startTarget(memAddrStart);
		
		// remove breakpoint
		removeBreakPoint(memAddrStart + nofInstr*4);
		// restore 1x4 bytes
		writeWord(memAddrStart, memValue);
		// restore r0, r1, pc
		setRegisterValue("R0", r0Stored);
		setRegisterValue("PC", pcStored);
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
