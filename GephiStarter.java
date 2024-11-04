import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import org.gephi.appearance.api.AppearanceController;
import org.gephi.appearance.api.AppearanceModel;
import org.gephi.appearance.api.Function;
import org.gephi.appearance.api.Partition;
import org.gephi.appearance.api.PartitionFunction;
import org.gephi.appearance.plugin.PartitionElementColorTransformer;
import org.gephi.appearance.plugin.RankingNodeSizeTransformer;
import org.gephi.appearance.plugin.palette.Palette;
import org.gephi.appearance.plugin.palette.PaletteManager;
import org.gephi.filters.api.FilterController;
import org.gephi.filters.api.Query;
import org.gephi.filters.api.Range;
import org.gephi.filters.plugin.graph.GiantComponentBuilder;
import org.gephi.filters.plugin.graph.DegreeRangeBuilder.DegreeRangeFilter;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.DirectedGraph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.GraphView;
import org.gephi.io.exporter.api.ExportController;
import org.gephi.io.importer.api.Container;
import org.gephi.io.importer.api.EdgeDirectionDefault;
import org.gephi.io.importer.api.ImportController;
import org.gephi.io.processor.plugin.DefaultProcessor;
import org.gephi.layout.plugin.force.StepDisplacement;
import org.gephi.layout.plugin.force.yifanHu.YifanHuLayout;
import org.gephi.layout.plugin.force.yifanHu.YifanHuProportional;
import org.gephi.layout.plugin.forceAtlas2.ForceAtlas2;
import org.gephi.layout.spi.Layout;
import org.gephi.preview.api.PreviewController;
import org.gephi.preview.api.PreviewModel;
import org.gephi.preview.api.PreviewProperty;
import org.gephi.preview.types.DependantColor;
import org.gephi.preview.types.DependantOriginalColor;
import org.gephi.preview.types.EdgeColor;
import org.gephi.preview.types.EdgeColor.Mode;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.statistics.plugin.Modularity;
import org.openide.util.Lookup;
//https://github.com/KiranGershenfeld/VisualizingTwitchCommunities/blob/AutoAtlasGeneration/AtlasGeneration/Java/App.java

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

//https://github.com/gephi/gephi-toolkit-demos/tree/master/src/main/java/org/gephi/toolkit/demos
public class GephiStarter {
    
    public static void main(String[] args) {
        var options = JsonParser.parseReader(new InputStreamReader(System.in)).getAsJsonObject();
        
        var file = new File(options.get("InFile").getAsString());
        var outFile = new File(options.get("OutFile").getAsString());
        
        //Init a project - and therefore a workspace
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.newProject();
        Workspace workspace = pc.getCurrentWorkspace();

        //Get models and controllers for this new workspace - will be useful later
        GraphModel graphModel = Lookup.getDefault().lookup(GraphController.class).getGraphModel();
        ImportController importController = Lookup.getDefault().lookup(ImportController.class);
        
        //Import file       
        Container container;
        try {
            container = importController.importFile(file);
            container.getLoader().setEdgeDefault(EdgeDirectionDefault.DIRECTED);   //Force DIRECTED
        } catch (Exception ex) {
            ex.printStackTrace();
            return;
        }

        //Append imported data to GraphAPI
        importController.process(container, new DefaultProcessor(), workspace);

        //See if graph is well imported
        DirectedGraph graph = graphModel.getDirectedGraph();
        System.out.println("Nodes: " + graph.getNodeCount());
        System.out.println("Edges: " + graph.getEdgeCount());

        //Remove nodes with degree < 2
        

        if (options.has("filters")) {
            var filters = options.get("filters").getAsJsonArray();
            for (var el : filters) {
                var filterOptions = el.getAsJsonObject();
                var name = filterOptions.get("name").getAsString();
                switch (name) {
                    case "GiantComponent":
                        filterGiantComponent(graphModel, workspace);
                        break;
                    case "Degree":
                        filterDegree(graphModel,filterOptions);
                        break;
                    default:
                        break;
                }
            }

        }
        graph = graphModel.getDirectedGraphVisible();
        System.out.println("After filtering, nodes: " + graph.getNodeCount() + " Edges: " + graph.getEdgeCount());

        var layouts = options.get("layouts").getAsJsonArray();
        for (var layoutEl : layouts) {
            var layout = layoutEl.getAsJsonObject();
            // System.out.println(layout.getAsString());
            var name = layout.get("name").getAsString();
            var stepsEl = layout.get("steps");
            int steps = stepsEl == null ? 200 : stepsEl.getAsInt();

            switch (name) {
                case "YifanHu" : {
                    applyYifanHu(graphModel, steps); break;
                }
                case "YifanHuProportional" : {
                    applyYifanHuProportional(graphModel, steps); break;
                }
                case "ForceAtlas2" : {
                    applyForceAtlas2(graphModel, layout); break;
                }
                //default : applyLayout(graphModel);
    
            }
        }
        

        if (options.has("rankNodesByDegree")) {
            rankNodesByDegree(graphModel, options.getAsJsonObject("rankNodesByDegree"));
        }

        
        
        if (options.has("partitionNodesBy")) {
            partitionNodes(graphModel, options.get("partitionNodesBy").getAsString());
        }

        // var previewOpts = options.has("preview") ? options.getAsJsonObject("preview") : new JsonObject();
        if (options.has("preview")) {
            setGraphPreview(options.getAsJsonObject("preview"));
        }

        //Export
        ExportController ec = Lookup.getDefault().lookup(ExportController.class);
        try {
            
            ec.exportFile(outFile);
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }
    }

    private static void filterGiantComponent(GraphModel graphModel, Workspace workspace) {
        FilterController filterController = Lookup.getDefault().lookup(FilterController.class);
        var giantComponent = new GiantComponentBuilder().getFilter(workspace);
        var query = filterController.createQuery(giantComponent);
        var view = filterController.filter(query);
        graphModel.setVisibleView(view);
    }
    private static void filterDegree(GraphModel graphModel, JsonObject filterOptions) {
        FilterController filterController = Lookup.getDefault().lookup(FilterController.class);
        DegreeRangeFilter degreeFilter = new DegreeRangeFilter();
        degreeFilter.init(graphModel.getGraph());

        int minDegree = filterOptions.has("minDegree") ? filterOptions.get("minDegree").getAsInt() : 2;
        degreeFilter.setRange(new Range(minDegree, Integer.MAX_VALUE));
        Query query = filterController.createQuery(degreeFilter);
        GraphView view = filterController.filter(query);
        graphModel.setVisibleView(view);
    }
    private static void applyForceAtlas2(GraphModel graphModel, JsonObject layoutOptions) {
        int steps = layoutOptions.has("steps") ? layoutOptions.get("steps").getAsInt() : 200;
        
        ForceAtlas2 layout = new ForceAtlas2(null);
        layout.setGraphModel(graphModel);
        layout.resetPropertiesValues();
        if (layoutOptions.has("scaling")) {
            var scaling = layoutOptions.get("scaling").getAsDouble();
            layout.setScalingRatio(scaling);
        }
        
        runAlgoFor(layout, steps);
        return;
    }

    private static void applyYifanHu(GraphModel graphModel, int steps) {
        YifanHuLayout layout = new YifanHuLayout(null, new StepDisplacement(1f));
        
        layout.resetPropertiesValues();
        layout.setGraphModel(graphModel);
        // layout.setOptimalDistance(100f);
        
        for (var prop : layout.getProperties()) {
            try {
                var name = prop.getProperty().getName();
                var value = prop.getProperty().getValue();
                System.out.println(name+" = "+value);
            } catch (Exception e) {e.printStackTrace();}
        }
        runAlgoFor(layout,steps);
    }

    private static void applyYifanHuProportional(GraphModel graphModel, int steps) {
        var layout =  new YifanHuProportional().buildLayout();
        layout.resetPropertiesValues();
        layout.setGraphModel(graphModel);
        runAlgoFor(layout, steps);
    }

    private static void rankNodesByDegree(GraphModel graphModel, JsonObject rankingOptions) {
        AppearanceController appearanceController = Lookup.getDefault().lookup(AppearanceController.class);
        AppearanceModel appearanceModel = appearanceController.getModel();

        Function degreeRanking = appearanceModel.getNodeFunction(graphModel.defaultColumns()
            .degree(), RankingNodeSizeTransformer.class);
        RankingNodeSizeTransformer sizeTransformer = 
            (RankingNodeSizeTransformer) degreeRanking.getTransformer();

        int nodeMinSize = rankingOptions.has("minSize") ? rankingOptions.get("minSize").getAsInt() : 5;
        int nodeMaxSize = rankingOptions.has("maxSize") ? rankingOptions.get("maxSize").getAsInt() : nodeMinSize * 4;
        
        sizeTransformer.setMinSize(nodeMinSize);
        sizeTransformer.setMaxSize(nodeMaxSize);

        appearanceController.transform(degreeRanking);
    }
    private static void partitionNodes(GraphModel graphModel, String columnName) {
        if (columnName.equals("modularity_class")) {
            Modularity modularity = new Modularity();
            modularity.execute(graphModel);
        }
        AppearanceController appearanceController = Lookup.getDefault().lookup(AppearanceController.class);
        AppearanceModel appearanceModel = appearanceController.getModel();
        DirectedGraph graph = graphModel.getDirectedGraph();

        Column column = graphModel.getNodeTable().getColumn(columnName);
        Function func = appearanceModel.getNodeFunction(column, PartitionElementColorTransformer.class);
        Partition partition = ((PartitionFunction) func).getPartition();
        Palette palette = PaletteManager.getInstance().generatePalette(partition.size(graph));
        partition.setColors(graph, palette.getColors());
        appearanceController.transform(func);

    }

    
    public static void setGraphPreview(JsonObject previewOptions) {
        //Preview
        PreviewModel model = Lookup.getDefault().lookup(PreviewController.class).getModel();
        //Node Label Properties
        model.getProperties().putValue(PreviewProperty.SHOW_NODE_LABELS, Boolean.TRUE);
        model.getProperties().putValue(PreviewProperty.NODE_LABEL_PROPORTIONAL_SIZE, Boolean.TRUE);
        model.getProperties().putValue(PreviewProperty.NODE_LABEL_FONT, new Font("Arial", Font.PLAIN, 8));

        model.getProperties().putValue(PreviewProperty.NODE_LABEL_COLOR, new DependantOriginalColor(Color.WHITE));

        model.getProperties().putValue(PreviewProperty.NODE_LABEL_OUTLINE_SIZE, 4.0f);
        model.getProperties().putValue(PreviewProperty.NODE_LABEL_OUTLINE_OPACITY, 40);
        model.getProperties().putValue(PreviewProperty.NODE_LABEL_OUTLINE_COLOR, new DependantColor(Color.BLACK));
        
        //Edge Properties
        model.getProperties().putValue(PreviewProperty.SHOW_EDGES, Boolean.TRUE);
        
        var edgeColorEl = previewOptions.get("edgeColor");
        if (edgeColorEl != null) {
            Mode mode = Mode.valueOf(edgeColorEl.getAsString().toUpperCase());
            model.getProperties().putValue(PreviewProperty.EDGE_COLOR, new EdgeColor(mode));
        } else {
            model.getProperties().putValue(PreviewProperty.EDGE_COLOR, new DependantOriginalColor(Color.WHITE));
        }


        // model.getProperties().putValue(PreviewProperty.EDGE_COLOR, new EdgeColor(Mode.SOURCE));
        // model.getProperties().putValue(PreviewProperty.EDGE_THICKNESS, 1.0);
        // model.getProperties().putValue(PreviewProperty.EDGE_RESCALE_WEIGHT, Boolean.TRUE);
        // model.getProperties().putValue(PreviewProperty.EDGE_RESCALE_WEIGHT_MIN, 1);
        // model.getProperties().putValue(PreviewProperty.EDGE_RESCALE_WEIGHT_MAX, 20);
        model.getProperties().putValue(PreviewProperty.EDGE_OPACITY, 60);
        // model.getProperties().putValue(PreviewProperty.ARROW_SIZE, 5);
        


        //Image Properties
        model.getProperties().putValue(PreviewProperty.BACKGROUND_COLOR, Color.BLACK);

        return;
    }

    private static void runAlgoFor(Layout layout, int steps) {
        System.out.printf("Applying layout %s with %s steps %n",layout.getClass().getSimpleName(), steps); 
        layout.initAlgo();
        for (int k = 0; k < steps; k++) {
            layout.goAlgo();
        }
        layout.endAlgo();
    }
}
