// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;

@WebServlet("/solicitar-baja")
public class SolicitarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 4L; // Versión actualizada
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            jsonResponse.addProperty("message", "No hay una sesión de usuario activa. Por favor, inicia sesión de nuevo.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }
        String usuario = (String) session.getAttribute("usuario");

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false); 

            JsonObject body = gson.fromJson(request.getReader(), JsonObject.class);
            String plainPassword = body.get("password").getAsString();
            
            String hashedPassword = null;
            String sqlRowUuid = null; 

            String sqlSelect = "SELECT Clave, SqlRowUUID FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlSelect)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        hashedPassword = rs.getString("Clave");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }
            
            if (hashedPassword == null || !BCrypt.checkpw(plainPassword, hashedPassword)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                jsonResponse.addProperty("message", "La contraseña es incorrecta.");
                response.getWriter().write(jsonResponse.toString());
                LogUtil.logOperation(conn, "SOLICITUD_BAJA_FAIL", usuario, "Intento de baja con contraseña incorrecta.");
                conn.rollback();
                return;
            }

            Date fechaBajaSql = new Date(System.currentTimeMillis());
            String sqlUpdateBaja = "UPDATE voluntarios SET fecha_baja = ? WHERE Usuario = ?";
            try (PreparedStatement psUpdate = conn.prepareStatement(sqlUpdateBaja)) {
                psUpdate.setDate(1, fechaBajaSql);
                psUpdate.setString(2, usuario);
                psUpdate.executeUpdate();
            }

            if (sqlRowUuid != null && !sqlRowUuid.isEmpty()) {
                Map<String, Object> spData = new HashMap<>();
                String fechaBajaString = new SimpleDateFormat("yyyy-MM-dd").format(fechaBajaSql);
                spData.put("field_21", fechaBajaString); 
                SharepointReplicationUtil.replicate(conn, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
            }
            
            LogUtil.logOperation(conn, "BAJA_OK", usuario, "El usuario se ha dado de baja.");
            conn.commit(); 

            // --- INICIO DE LA NUEVA LÓGICA ---
            // 1. Invalidar la sesión actual para cerrar sesión
            session.invalidate();

            // 2. Preparar la respuesta JSON con la instrucción de redirección
            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "Tu solicitud de baja ha sido procesada correctamente. Serás redirigido a la página de inicio.");
            jsonResponse.addProperty("redirectUrl", "login.html");
            // --- FIN DE LA NUEVA LÓGICA ---

            response.getWriter().write(jsonResponse.toString());

        } catch (JsonSyntaxException | NullPointerException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.addProperty("message", "La solicitud no tiene el formato esperado.");
            response.getWriter().write(jsonResponse.toString());
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("message", "Error de base de datos al procesar la solicitud.");
            response.getWriter().write(jsonResponse.toString());
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("message", "Ha ocurrido un error inesperado: " + e.getMessage());
            response.getWriter().write(jsonResponse.toString());
        }
    }
}
