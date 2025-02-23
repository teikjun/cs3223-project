/**
 * prepares a random initial plan for the given SQL query
 **/

package qp.optimizer;

import qp.operators.*;
import qp.utils.*;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;

public class RandomInitialPlan {

    SQLQuery sqlquery;

    ArrayList<Attribute> projectlist;
    ArrayList<String> fromlist;
    ArrayList<Condition> selectionlist;   // List of select conditons
    ArrayList<Condition> joinlist;        // List of join conditions
    ArrayList<Attribute> groupbylist;
    ArrayList<Attribute> orderbylist;
    int numJoin;            // Number of joins in this query
    HashMap<String, Operator> tab_op_hash;  // Table name to the Operator
    Operator root;          // Root of the query plan tree

    public RandomInitialPlan(SQLQuery sqlquery) {
        this.sqlquery = sqlquery;
        projectlist = sqlquery.getProjectList();
        fromlist = sqlquery.getFromList();
        selectionlist = sqlquery.getSelectionList();
        joinlist = sqlquery.getJoinList();
        groupbylist = sqlquery.getGroupByList();
        orderbylist = sqlquery.getOrderByList();
        numJoin = joinlist.size();
    }

    /**
     * number of join conditions
     **/
    public int getNumJoins() {
        return numJoin;
    }

    /**
     * prepare initial plan for the query
     **/
    public Operator prepareInitialPlan() {

    	System.out.printf("NumJoin: %d", numJoin);
    	System.out.printf("fromList: %d", fromlist.size());

        tab_op_hash = new HashMap<>();
        createScanOp();
        createSelectOp();
        if (numJoin != 0) {
            createJoinOp();
        } else if (fromlist.size() > 1) {
        	createCrossProductOp();
        }
        
        if (sqlquery.getOrderByList().size() > 0) {
            createOrderByOp();
        }
        if (sqlquery.getGroupByList().size() > 0) {
            createGroupByOp();
        }
        createProjectOp();
        if (sqlquery.isDistinct()) {
    		createDistinctOp();
    	}
        return root;
    }

    /**
     * Create Scan Operator for each of the table
     * * mentioned in from list
     **/
    public void createScanOp() {
        int numtab = fromlist.size();
        Scan tempop = null;
        for (int i = 0; i < numtab; ++i) {  // For each table in from list
            String tabname = fromlist.get(i);
            Scan op1 = new Scan(tabname, OpType.SCAN);
            tempop = op1;

            /** Read the schema of the table from tablename.md file
             ** md stands for metadata
             **/
            String filename = tabname + ".md";
            try {
                ObjectInputStream _if = new ObjectInputStream(new FileInputStream(filename));
                Schema schm = (Schema) _if.readObject();
                op1.setSchema(schm);
                _if.close();
            } catch (Exception e) {
                System.err.println("RandomInitialPlan:Error reading Schema of the table " + filename);
                System.err.println(e);
                System.exit(1);
            }
            tab_op_hash.put(tabname, op1);
        }

        // 12 July 2003 (whtok)
        // To handle the case where there is no where clause
        // selectionlist is empty, hence we set the root to be
        // the scan operator. the projectOp would be put on top of
        // this later in CreateProjectOp
        if (selectionlist.size() == 0) {
            root = tempop;
            return;
        }

    }

    /**
     * Create Selection Operators for each of the
     * * selection condition mentioned in Condition list
     **/
    public void createSelectOp() {
        Select op1 = null;
        for (int j = 0; j < selectionlist.size(); ++j) {
            Condition cn = selectionlist.get(j);
            if (cn.getOpType() == Condition.SELECT) {
                String tabname = cn.getLhs().getTabName();
                Operator tempop = (Operator) tab_op_hash.get(tabname);
                op1 = new Select(tempop, cn, OpType.SELECT);
                /** set the schema same as base relation **/
                op1.setSchema(tempop.getSchema());
                modifyHashtable(tempop, op1);
            }
        }

        /** The last selection is the root of the plan tre
         ** constructed thus far
         **/
        if (selectionlist.size() != 0)
            root = op1;
    }

    /**
     * create join operators
     **/
    public void createJoinOp() {
        BitSet bitCList = new BitSet(numJoin);
        int jnnum = RandNumb.randInt(0, numJoin - 1);
        Join jn = null;

        /** Repeat until all the join conditions are considered **/
        while (bitCList.cardinality() != numJoin) {
            /** If this condition is already consider chose
             ** another join condition
             **/
            while (bitCList.get(jnnum)) {
                jnnum = RandNumb.randInt(0, numJoin - 1);
            }
            Condition cn = (Condition) joinlist.get(jnnum);
            String lefttab = cn.getLhs().getTabName();
            String righttab = ((Attribute) cn.getRhs()).getTabName();
            Operator left = (Operator) tab_op_hash.get(lefttab);
            Operator right = (Operator) tab_op_hash.get(righttab);
            jn = new Join(left, right, cn, OpType.JOIN);
            jn.setNodeIndex(jnnum);
            Schema newsche = left.getSchema().joinWith(right.getSchema());
            jn.setSchema(newsche);

            /** randomly select a join type**/
            int numJMeth = JoinType.numJoinTypes();
            int joinMeth = RandNumb.randInt(0, numJMeth - 1);
            joinMeth = JoinType.SORTMERGE;
            jn.setJoinType(joinMeth);
            modifyHashtable(left, jn);
            modifyHashtable(right, jn);
            bitCList.set(jnnum);
        }

        /** The last join operation is the root for the
         ** constructed till now
         **/
        if (numJoin != 0)
            root = jn;
    }
    
    /**
     * create cross product operators
     **/
    public void createCrossProductOp() {
    	int numCross = fromlist.size()-1; 
        BitSet bitCList = new BitSet(numCross);
        int crnum = RandNumb.randInt(0, numCross - 1);
        Join jn = null;

        /** Repeat until all the join conditions are considered **/
        while (bitCList.cardinality() != numCross) {
            /** If this condition is already consider chose
             ** another join condition
             **/
            while (bitCList.get(crnum)) {
                crnum = RandNumb.randInt(0, numCross - 1);
            }
            String lefttab = fromlist.get(crnum);
            String righttab = fromlist.get(crnum+1);
            Operator left = (Operator) tab_op_hash.get(lefttab);
            Operator right = (Operator) tab_op_hash.get(righttab);
            jn = new Join(left, right, new Condition(0), OpType.JOIN);
            jn.setNodeIndex(crnum);
            Schema newsche = left.getSchema().joinWith(right.getSchema());
            jn.setSchema(newsche);

            /** use cross product for join type**/
            int joinMeth = JoinType.CROSSPRODUCT;
            jn.setJoinType(joinMeth);
            modifyHashtable(left, jn);
            modifyHashtable(right, jn);
            bitCList.set(crnum);
        }

        /** The last join operation is the root for the
         ** constructed till now
         **/
        root = jn;
    }

    public void createProjectOp() {
        Operator base = root;
        if (projectlist == null)
            projectlist = new ArrayList<Attribute>();
        if (!projectlist.isEmpty()) {
            root = new Project(base, projectlist, OpType.PROJECT);
            Schema newSchema = base.getSchema().subSchema(projectlist);
            root.setSchema(newSchema);
        }
    }

    /**
     * Create GroupBy operator
     **/
    public void createGroupByOp() {
        Operator base = root;
        if (groupbylist == null) {
            groupbylist = new ArrayList<Attribute>();
        }
        if (!groupbylist.isEmpty()) {
            root = new GroupBy(base, groupbylist, OpType.GROUPBY);
            ((GroupBy) root).setNumBuff(BufferManager.getBuffersPerJoin());
            root.setSchema(base.getSchema());
        }
    }

    public void createOrderByOp() {
        Operator base = root;
        if (orderbylist == null)
            orderbylist = new ArrayList<Attribute>();
        if (!orderbylist.isEmpty()) {
            root = new Order(base, orderbylist, OpType.ORDER, sqlquery.isDesc());
            Schema schema = base.getSchema();
            root.setSchema(schema);
        }
    }

    /**
     * Create Distinct operator
     **/
    public void createDistinctOp() {
        Operator base = root;
        if (projectlist == null) {
            projectlist = new ArrayList<Attribute>();
        }
        if (!projectlist.isEmpty()) {
            root = new Distinct(base, projectlist, OpType.DISTINCT);
            ((Distinct) root).setNumBuff(BufferManager.getBuffersPerJoin());
            root.setSchema(base.getSchema());
        }
    }

    private void modifyHashtable(Operator old, Operator newop) {
        for (HashMap.Entry<String, Operator> entry : tab_op_hash.entrySet()) {
            if (entry.getValue().equals(old)) {
                entry.setValue(newop);
            }
        }
    }
}
