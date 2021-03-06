/*
 * created: 2010
 *
 * This file is part of Artemis
 *
 * Copyright(C) 2010  Genome Research Limited
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or(at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 */

package uk.ac.sanger.artemis.components.variant;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Vector;
import java.util.regex.Pattern;

import net.sf.samtools.util.BlockCompressedInputStream;


class BCFReader extends AbstractVCFReader
{
  public static final int TAD_LIDX_SHIFT = 13; // linear index shift
  private static Pattern formatPattern = Pattern.compile("[^0-9]+");
  private BlockCompressedInputStream is;
  private FileInputStream indexFileStream;
  private List<BCFIndex> idx;
  
  // header information and names
  private String[] seqNames;
  private String[] sampleNames;
  private int nsamples;
  private String metaData;
  private String fileName;
  
  /**
   * @param bcf  BCF file
   * @throws IOException
   */
  public BCFReader(File bcf) throws IOException
  {
    is = new BlockCompressedInputStream(bcf);
    readHeader();
    
    File bcfIndex = new File(bcf.getAbsolutePath()+".bci");
    indexFileStream = new FileInputStream(bcfIndex);
    idx = loadIndex();
    fileName = bcf.getAbsolutePath();
  }

  protected void seek(long off) throws IOException
  {
    is.seek(off);
  }
  
  protected VCFRecord nextRecord(String chr, int beg, int end) throws IOException
  {
    try
    {
      VCFRecord bcfRecord = readVCFRecord();
      if(chr != null && !bcfRecord.getChrom().equals(chr))
        return null;

      if(bcfRecord.getPos() >= beg && bcfRecord.getPos() <= end)
        return bcfRecord;
      else if(bcfRecord.getPos() < beg)
      {
        while( (bcfRecord = readVCFRecord()).getPos() <= beg )
        {
          if(chr != null && !bcfRecord.getChrom().equals(chr))
            return null;

          if(bcfRecord.getPos() >= beg && bcfRecord.getPos() <= end)
            return bcfRecord;
        }
        if(bcfRecord.getPos() >= beg && bcfRecord.getPos() <= end)
          return bcfRecord;
      }
    } 
    catch(Exception e)
    {
      if(is.read() != -1)  // eof
        e.printStackTrace();
    }
    
    return null;
  }
  
  protected void close() throws IOException
  {
    is.close();
    indexFileStream.close();
  }
  
  private void readHeader() throws IOException
  {
    byte[] magic = new byte[4];
    is.read(magic);

    String line = new String(magic);
    if(!line.equals("BCF\4"))
    {
      throw new IOException("Not BCF format.");
    }

    // sequence names
    seqNames = getArray(readInt(is));

    // sample names
    sampleNames = getArray(readInt(is));
    nsamples = sampleNames.length;

    int len = readInt(is);   
    byte meta[] = new byte[len];
    is.read(meta);

    StringBuffer buff = new StringBuffer();
    for(int i=0; i<meta.length; i++)
      buff.append((char)meta[i]);

    metaData = buff.toString();
  }
  
  protected String headerToString()
  {
    StringBuffer head = new StringBuffer();
    head.append("##fileformat=VCFv4.0\n");
    head.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t");
    
    for(int i=0; i<sampleNames.length; i++)
      head.append(sampleNames[i]+" ");
    return head.toString();
  }
  
  /**
   * Given the length of the concatenated names, that are NULL padded
   * construct a list of the Strings.
   * @param len
   * @return
   * @throws IOException
   */
  private String[] getArray(int len) throws IOException
  {
    byte b[] = new byte[len];
    is.read(b);
    
    List<String> names = new Vector<String>();
    StringBuffer buff = new StringBuffer();
    for(int i=0; i<b.length; i++)
    {
      if(b[i] != 0)
        buff.append((char)b[i]);
      else if(buff.length() > 0)
      {
        names.add(buff.toString());
        buff = new StringBuffer();
      }
    }
    
    String[] arr = new String[names.size()];
    for(int i=0; i< arr.length; i++)
      arr[i] = names.get(i);
    return arr;
  }
  
  private VCFRecord readVCFRecord() throws IOException
  {
    VCFRecord bcfRecord = new VCFRecord();
    bcfRecord.setChrom( seqNames[readInt(is)] );    
    bcfRecord.setPos ( readInt(is)+1 );
    bcfRecord.setQuality( readFloat(is) );
    
    int slen = readInt(is);
    byte[] str = new byte[slen];
    is.read(str);

    getParts(str, bcfRecord);
    
    if(formatPattern.matcher(bcfRecord.getFormat()).matches())
    {
      int n_alleles = bcfRecord.getAlt().getNumAlleles();
      int nc  = (int) (n_alleles * ((float)(((float)n_alleles+1.f)/2.f)));

      if(bcfRecord.getAlt().isNonVariant())
        nc = 1;

      String fmts[] = bcfRecord.getFormat().split(":");
      bcfRecord.setData( new String[nsamples][fmts.length] );
      
      for(int j=0; j<nsamples; j++)
      {
        for(int k=0; k<fmts.length; k++)
        {
          int nb = getByteSize(fmts[k],nc);
          str = new byte[nb];
          is.read(str);
        
          final String value;
          if(fmts[k].equals("GT"))
            value = getGTString(str[0]);
          else if(fmts[k].equals("PL"))
            value = getPLString(str, nc);
          else if(fmts[k].equals("DP")||fmts[k].equals("SP")||fmts[k].equals("GQ"))
            value = Integer.toString(byteToInt(str[0]));
          else
            value = new String(str);
  
          bcfRecord.getData()[j][k] = value;
        }
      }
      
    }
    
    return bcfRecord;
  }
  
  /**
   * Make a string from the byte array (expanding NULL padding) to
   * determine the parts:- ID+REF+ALT+FILTER+INFO+FORMAT.
   * @param b
   * @return
   */
  private void getParts(byte[] b, VCFRecord bcfRecord)
  {
    StringBuffer buff = new StringBuffer();
    for(int i=0; i<b.length; i++)
    {
      if(i == 0 && b[i] == 0)
        buff.append(". ");
      else if(b[i] == 0 && b[i-1] == 0)
        buff.append(" . ");
      else if(b[i] == 0)
        buff.append(" ");
      else
        buff.append((char)b[i]);
    }

    String parts[] = buff.toString().replace("  ", " ").split(" ");
    bcfRecord.setID( parts[0] );
    bcfRecord.setRef( parts[1] );
    bcfRecord.setAlt( parts[2] );
    bcfRecord.setFilter( parts[3] );
    bcfRecord.setInfo( parts[4] );
    bcfRecord.setFormat( parts[5] );
  }
  
  /**
   * DP uint16 t[n] Read depth
   * GL float[n*x] Log10 likelihood of data; x = m(m+1)/2 , m = #{alleles}
   * GT uint8 t[n] phase<<6 | allele1<<3 | allele2
   * GQ uint8 t[n] Genotype quality
   * HQ uint8 t[n*2] Haplotype quality
   * PL uint8 t[n*x] Phred-scaled likelihood of data
   * misc int32 t+char* NULL padded concatenated strings (integer equal to the length)
   * @param tag
   * @param nsamples
   * @param nc
   * @return
   */
  private int getByteSize(String tag, int nc)
  {
    if(tag.equals("DP"))        // Read depth
      return 2*nsamples;        // uint16_t[n]
    else if(tag.equals("GL"))   // Log10 likelihood
      return 4*nsamples*nc;     // float[nsamples*x]
    else if(tag.equals("GT"))   // phase<<6 | allele1<<3 | allele2
      return nsamples;          // uint8_t[n]
    else if(tag.equals("GQ"))   // Genotype quality
      return nsamples;          // uint8_t[n]
    else if(tag.equals("HQ"))   // Haplotype quality
      return 2*nsamples;        // uint8_t[n*2]
    else if(tag.equals("PL"))   // Phred-scaled likelihood
      return nsamples*nc;       // uint8_t[n*x]
    else if(tag.equals("SP"))   // 
      return nsamples;          // uint8_t[n]
    else                        // misc
      return 4*nsamples;        // uint32_t+char*
  }
  
  
  private String getPLString(byte[] b, int nc)
  {
    StringBuffer buff = new StringBuffer();
    for(int i=0;i<b.length; i++)
    {
      buff.append(byteToInt(b[i]));
      if(i<b.length-1)
        buff.append(",");
    }
    return buff.toString();
  }
  
  /**
   * GT genotype, allele values separated by / or |, i.e.
   * unphased or phased.
   * @param b
   * @return
   */
  private String getGTString(byte b)
  {
    return ((b >> 3 & 7) + ( ((b >> 6 & 1 )== 1 ) ? "|" : "/") + (b & 7));
  }
 
  private int byteToInt(byte b)
  {
    return (int)(b & 0xFF);
  }
  
  protected static void writeVCF(Writer writer, String vcfFileName) throws IOException
  {
    BCFReader reader = new BCFReader(new File(vcfFileName));
    writer.write( reader.headerToString()+"\n" );
    
    int sbeg = 0;
    int send = Integer.MAX_VALUE;
    VCFRecord record;
    
    while( (record = reader.nextRecord(null, sbeg, send)) != null)
      writer.write(record.toString()+"\n");
    writer.close();
    reader.close();
  }
  
  protected List<BCFIndex> loadIndex() throws IOException
  {
    BlockCompressedInputStream is = new BlockCompressedInputStream(indexFileStream);
    byte[] magic = new byte[4];
    is.read(magic);
    
    if(!new String(magic).equals("BCI\4"))
      System.err.println("Not a BCF index file:: "+new String(magic));
    
    int n = readInt(is);
    List<BCFIndex> idx = new Vector<BCFIndex>(n);
    
    for(int i=0; i<n; i++)
    {
      BCFIndex idx2 = new BCFIndex();
      idx2.n = readInt(is);
      idx2.index2_offset = new long[idx2.n];
      
      for(int j=0; j<idx2.n; j++)
        idx2.index2_offset[j] = readLong(is);

      idx.add(idx2);
    }
    return idx;
  }
  
  protected int getSeqIndex(String chr)
  {
    for(int i=0; i<seqNames.length; i++)
    {
      if(seqNames[i].equals(chr))
        return i;
    }
        
    return -1;
  }
  
  protected long queryIndex(int tid, int beg)
  {
    long min_off = -1;
    if (beg < 0) 
      beg = 0;
   
    long offset[] = idx.get(tid).index2_offset;
    int i;

    try
    {
      for(i = beg>>TAD_LIDX_SHIFT; i < idx.get(tid).n && offset[i] == 0; ++i);
      min_off = (i == idx.get(tid).n)? offset[idx.get(tid).n-1] : offset[i];
    }
    catch(ArrayIndexOutOfBoundsException e)
    {
      return offset[offset.length-1];
    }
    return min_off;
  }

  protected String getMetaData()
  {
    return metaData;
  }

  protected String[] getSeqNames()
  {
    return seqNames;
  }
  
  protected String getFileName()
  {
    return fileName;
  }
  
  protected BCFReaderIterator query(String chr, int sbeg, int send) throws IOException
  {
    return new BCFReaderIterator(chr, sbeg, send);
  }
  
  public class BCFReaderIterator
  {
    private String chr;
    private int sbeg;
    private int send;
    private int count = 0;
    
    public BCFReaderIterator(String chr, int sbeg, int send)
    {
      this.chr = chr;
      this.sbeg = sbeg;
      this.send = send;
    }
    
    private boolean seekPosition() throws IOException
    {
      int bid = getSeqIndex(chr);
      if(bid < 0)
      {
        VCFview.logger4j.debug(chr+" NOT FOUND");
        return false;
      }

      long off = queryIndex(bid, sbeg);
      seek(off);
      return true;
    }
    
    public VCFRecord next() throws IOException
    {
      if(count == 0 && !seekPosition())
        return null;

      count+=1;
      return nextRecord(chr, sbeg, send);
    }
  }
  
  
  public static void main(String args[])
  {
    try
    {
      int sbeg = 0;
      int send = Integer.MAX_VALUE;
      String chr = null;
      if(args.length > 1)
      {
        String parts[] = args[1].split(":");
        chr = parts[0];
        
        String rgn[] = parts[1].split("-");
        sbeg = Integer.parseInt(rgn[0]);
        send = Integer.parseInt(rgn[1]);
      }
      
      BCFReader reader = new BCFReader(new File(args[0]));
      int bid = 0;
      if(chr != null)
        bid = reader.getSeqIndex(chr);
      
      long off = reader.queryIndex(bid, sbeg);
      reader.seek(off);

      System.out.println(reader.headerToString());
      VCFRecord bcfRecord;
      while( (bcfRecord = reader.nextRecord(chr, sbeg, send)) != null )
      {
        System.out.println(bcfRecord.getChrom());
        if(chr != null && bcfRecord.getChrom().equals(chr))
          System.out.println(bcfRecord.toString());
        else
          break;
      }
          
      
      reader.close();
    }
    catch (IOException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

}

class BCFIndex
{
  int n;
  long index2_offset[];
}