import com.pivotal.pxf.api.accessors.ReadAccessor;
import com.pivotal.pxf.api.analyzers.Analyzer;
import com.pivotal.pxf.api.analyzers.DataSourceStatsInfo;
import com.pivotal.pxf.api.utilities.InputData;

/*
 * Class that defines getting statistics for ANALYZE.
 * getEstimatedStats returns statistics for a given path
 * (block size, number of blocks, number of tuples).
 * Used when calling ANALYZE on a GPXF external table,
 * to get table's statistics that are used by the optimizer to plan queries. 
 * Dummy implementation, for documentation
 */
public class DummyAnalyzer extends Analyzer {
    public DummyAnalyzer(InputData metaData) {
        super(metaData);
    }

    /*
     * path is a data source URI that can appear as a file name, a directory name  or a wildcard
     * returns the data statistics in json format
     */
    @Override
    public DataSourceStatsInfo getEstimatedStats(String data, ReadAccessor accessor) throws Exception {
        return new DataSourceStatsInfo(160000 /* disk block size in bytes */,
                3 /* number of disk blocks */,
                6 /* total number of rows */);
    }
}