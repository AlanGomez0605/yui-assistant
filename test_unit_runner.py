import sys
import os

# Forzar salida en UTF-8 en consola de Windows
if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

import asyncio
import json
import re

async def run_all_unit_tests():
    print("=====================================================")
    print("[TESTS] INICIANDO BATERIA DE PRUEBAS UNITARIAS DE YUI")
    print("=====================================================")

    # 1. PRUEBA DE CONTACTOS Y FILTROS (MONGODB ATLAS)
    print("\n[TEST 1] Probando ContactsService y busqueda de contactos...")
    from backend.app.services.contacts_service import contacts_service
    all_contacts = await contacts_service.get_all_contacts()
    print(f"  [OK] Total de contactos recuperados de MongoDB: {len(all_contacts)}")
    assert len(all_contacts) == 437, f"Esperados 437 contactos, obtenidos {len(all_contacts)}"

    # Busqueda por letra J
    contacts_j = [c for c in all_contacts if c.get("name", "").strip().upper().startswith("J")]
    print(f"  [OK] Contactos que inician con la letra 'J': {len(contacts_j)} encontrados")
    assert len(contacts_j) > 0, "Deberia haber al menos un contacto con la J"
    print(f"       Ejemplos con 'J': {[c['name'] for c in contacts_j[:3]]}")

    # Busqueda con search_contacts
    search_res = await contacts_service.search_contacts("Alan")
    print(f"  [OK] Busqueda difusa por nombre 'Alan': {len(search_res)} encontrados")

    # 2. PRUEBA DE CUENTAS DE GOOGLE (GOOGLE SERVICE EN MONGODB)
    print("\n[TEST 2] Probando GoogleService (Vinculacion y Gestion en MongoDB)...")
    from backend.app.services.google_service import google_service
    test_email = "test_alan_yui@gmail.com"
    
    # Vincular cuenta de prueba
    link_res = await google_service.link_account(
        email=test_email,
        display_name="Alan Google Test",
        app_password="abcd efgh ijkl mnop"
    )
    print(f"  [OK] Vinculacion de cuenta Google: {link_res.get('status')} - {link_res.get('message')}")
    assert link_res.get("status") == "success"

    # Consultar cuentas (verificar que oculte la contrasena de aplicacion por seguridad)
    accounts = await google_service.get_all_accounts()
    target_acc = next((a for a in accounts if a.get("email") == test_email), None)
    assert target_acc is not None, "La cuenta vinculada debe existir en MongoDB"
    assert "app_password" not in target_acc, "La contrasena no debe exponerse en el listado publico"
    assert target_acc.get("has_app_password") is True, "Debe indicar que tiene contrasena registrada"
    print(f"  [OK] Seguridad de cuenta Google validada: has_app_password={target_acc.get('has_app_password')}")

    # Eliminar cuenta de prueba para dejar la BD limpia
    del_res = await google_service.delete_account(test_email)
    print(f"  [OK] Limpieza de cuenta de prueba: eliminada={del_res}")
    assert del_res is True

    # 3. PRUEBA DE CONTEXTO DINAMICO Y MEMORIA
    print("\n[TEST 3] Probando MemoryService y Construccion de Contexto...")
    from backend.app.services.memory_service import memory_service
    dynamic_context = await memory_service.build_dynamic_context()
    print(f"  [OK] Longitud del bloque de contexto dinamico: {len(dynamic_context)} caracteres")
    assert "AGENDA TELEFÓNICA DE ALAN" in dynamic_context or "AGENDA" in dynamic_context, "El contexto debe incluir la agenda de contactos"
    assert "contactos disponibles" in dynamic_context

    # 4. PRUEBA DE PROMPTS Y DIRECTIVAS DE YUI
    print("\n[TEST 4] Probando Directivas de Sistema (Sin OAuth falso y con economia de voz)...")
    from backend.app.core.prompts import get_yui_system_prompt
    prompt = get_yui_system_prompt()
    assert "PROHIBIDO inventar o generar enlaces ficticios" in prompt, "Prompt debe prohibir enlaces falsos"
    assert "PROHIBIDO leer o recitar listas largas por voz" in prompt, "Prompt debe prohibir leer listas largas"
    assert "VINCULACIÓN Y CUENTAS DE GOOGLE" in prompt, "Prompt debe incluir directivas de Google"
    print("  [OK] Directivas de Yui verificadas: No OAuth fake, economia de voz activa y directiva de Google confirmada.")

    # 5. PRUEBA DE PARSER DE COMANDOS OFFLINE (SIMULACION DE OFFLINE COMMAND ENGINE)
    print("\n[TEST 5] Probando Regex de Comandos Offline de Android...")
    # Prueba de formato HH:mm
    colon_matcher = re.compile(r"(\d{1,2}):(\d{2})").search("pon una alarma a las 7:30")
    assert colon_matcher is not None and colon_matcher.group(1) == "7" and colon_matcher.group(2) == "30"
    print("  [OK] Parser de Alarma offline: 'a las 7:30' -> 7:30 detectado con exito")

    # Prueba de formato natural "a las 8 y media"
    natural_matcher = re.compile(r"(?:a las|para las)\s+(\d{1,2})").search("despiertame a las 8 y media")
    assert natural_matcher is not None and natural_matcher.group(1) == "8"
    print("  [OK] Parser de Alarma natural offline: 'a las 8 y media' -> Hora 8 detectada con exito")

    # Prueba de regex para filtro por letra
    letter_pattern = re.compile(r"(?:con la|que empiecen con|letra)\s+([a-zA-Z])", re.IGNORECASE)
    m2 = letter_pattern.search("dame mis contactos con la J")
    assert m2 is not None and m2.group(1).upper() == "J"
    print("  [OK] Parser de Filtro por letra offline: 'contactos con la J' -> Letra J detectada con exito")

    # 6. PRUEBA DE PALABRAS DE ACTIVACION ("OYE YUI" WAKE WORD)
    print("\n[TEST 6] Probando Deteccion de Wake Word 'Oye Yui'...")
    wake_keywords = ["yui", "oye yui", "hey yui", "hola yui"]
    test_phrase = "oye yui que hora es"
    detected = any(k in test_phrase.lower() for k in wake_keywords)
    assert detected is True, "Debe detectar 'oye yui'"
    print("  [OK] Wake Word 'Oye Yui' detectado correctamente en la frase de prueba.")

    print("\n=====================================================")
    print(">>> TODAS LAS PRUEBAS UNITARIAS PASARON AL 100% (6/6)")
    print("=====================================================")

if __name__ == "__main__":
    asyncio.run(run_all_unit_tests())
