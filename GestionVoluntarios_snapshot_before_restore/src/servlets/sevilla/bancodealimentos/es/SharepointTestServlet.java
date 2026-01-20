package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/test-sharepoint-insert")
public class SharepointTestServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        out.println("--- INICIANDO PRUEBA DE INSERCIÃ“N EN SHAREPOINT ---");

        try {
            String siteId = SharepointUtil.SP_SITE_ID_VOLUNTARIOS;
            String listName = "campanas";

            out.println("Site ID: " + siteId);
            out.println("Lista: " + listName);

            // Paso 1: Crear el mapa de datos con la mÃ­nima informaciÃ³n posible
            Map<String, Object> spData = new HashMap<>();
            spData.put("Title", "CampaÃ±a de Prueba desde Servlet");
            // No aÃ±adimos mÃ¡s campos para minimizar la superficie del error
            out.println("Datos a enviar: " + spData.toString());

            // Paso 2: Construir el objeto FieldValueSet
            FieldValueSet fields = new FieldValueSet();
            fields.setAdditionalData(spData);
            out.println("FieldValueSet construido con Ã©xito.");

            // Paso 3: Llamar al mÃ©todo de creaciÃ³n de SharepointUtil
            out.println("Intentando llamar a SharepointUtil.createListItem...");
            SharepointUtil.createListItem(siteId, listName, fields);
            out.println("Â¡Ã‰XITO! La llamada a SharepointUtil.createListItem se completÃ³ sin excepciones.");
            out.println("Se ha creado un nuevo elemento en la lista 'campanas'.");

        } catch (Exception e) {
            out.println("\n--- Â¡ERROR! --- ");
            out.println("OcurriÃ³ una excepciÃ³n durante la prueba:");
            e.printStackTrace(out); // Imprime la traza completa del error en la respuesta
        }

        out.println("\n--- PRUEBA FINALIZADA ---");
    }
}


